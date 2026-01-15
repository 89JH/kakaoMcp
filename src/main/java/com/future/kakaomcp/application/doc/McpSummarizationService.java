package com.future.kakaomcp.application.doc;

import com.future.kakaomcp.domain.doc.ChunkingService;
import com.future.kakaomcp.infrastructure.mcp.McpRelayService;
import com.future.kakaomcp.infrastructure.mcp.McpToolName;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 문서/텍스트 요약 엔진
 * - 본문만 chunking (프롬프트가 chunk로 섞여 누출되는 문제 방지)
 * - MCP tool 호출 결과는 "text"만 안전 추출
 * - SummaryOutputCleaner로 최종 응답 정리(프롬프트/마커/CallToolResult 방어)
 *
 * ✅ 변경점:
 * - mode가 비어 있을 때(= 호출자가 안 주는 경우) URL/FILE + 긴 입력이면 final로 "자동 승격" 옵션 지원
 * - mode는 Node MCP로 항상 전달 (abstract/final)
 */
@Service
public class McpSummarizationService {

    private final ChunkingService chunkingService;
    private final McpRelayService mcpRelayService;
    private final PromptTemplateBuilder promptBuilder;

    // chunk 요약 결과 최대 길이(병합 요약 단계 입력 텍스트 관리)
    private final int chunkSummaryMaxChars;

    // merged(최종 병합) 요약 결과 최대 길이(응답 길이 관리)
    private final int mergedSummaryMaxChars;

    // URL에서 대략적으로 허용할 최대 청크 수 (너무 길면 비용/시간 급증)
    private final int maxChunks;

    // ✅ mode 자동승격 옵션
    private final boolean autoFinalEnabled;
    private final int autoFinalThresholdChars;

    public McpSummarizationService(
            ChunkingService chunkingService,
            McpRelayService mcpRelayService,
            PromptTemplateBuilder promptBuilder,
            @Value("${app.summarize.chunk.summary-max-chars:1800}") int chunkSummaryMaxChars,
            @Value("${app.summarize.final.max-chars:6000}") int mergedSummaryMaxChars,
            @Value("${app.summarize.url.max-chunks:5}") int maxChunks,

            // ✅ 새 설정(없으면 기본 off/값 사용)
            @Value("${app.summarize.auto-final.enabled:false}") boolean autoFinalEnabled,
            @Value("${app.summarize.auto-final.threshold-chars:12000}") int autoFinalThresholdChars
    ) {
        this.chunkingService = chunkingService;
        this.mcpRelayService = mcpRelayService;
        this.promptBuilder = promptBuilder;
        this.chunkSummaryMaxChars = Math.max(200, chunkSummaryMaxChars);
        this.mergedSummaryMaxChars = Math.max(400, mergedSummaryMaxChars);
        this.maxChunks = Math.max(1, maxChunks);

        this.autoFinalEnabled = autoFinalEnabled;
        this.autoFinalThresholdChars = Math.max(1000, autoFinalThresholdChars);
    }

    public record Result(String summaryText, Map<String, Object> meta) {}

    public Result summarize(String text, String mode, SummarizeMetadataContext ctx) {
        String safeText = (text == null) ? "" : text.trim();
        if (safeText.isBlank()) {
            return new Result("", Map.of("chunkCount", 0));
        }

        SummarizeMetadataContext safeCtx = (ctx == null) ? SummarizeMetadataContext.empty() : ctx;

        // ✅ mode 정규화 + (옵션) 자동 승격
        String effectiveMode = resolveMode(mode, safeCtx, safeText);

        // ✅ chunking은 "본문"만 (프롬프트를 섞지 않음)
        List<String> chunks = chunkingService.chunk(safeText);
        if (chunks == null) chunks = List.of(safeText);

        // URL/FILE에서 너무 길면 maxChunks 제한(비용/시간 폭발 방지)
        if (safeCtx.sourceType() == SummarizeMetadataContext.SourceType.URL
                && chunks.size() > maxChunks) {
            chunks = chunks.subList(0, maxChunks);
        }

        // chunk가 1개면 바로 요약
        if (chunks.size() <= 1) {
            String prompt = promptBuilder.build(effectiveMode, safeCtx, PromptTemplateBuilder.InputType.TEXT, safeText);
            String out = callSummarizeTool(prompt, effectiveMode, mergedSummaryMaxChars);
            String cleaned = SummaryOutputCleaner.clean(out);
            return new Result(cleaned, Map.of(
                    "chunkCount", chunks.size(),
                    "mode", effectiveMode
            ));
        }

        // 1) chunk별 요약(짧게)
        List<String> chunkSummaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String body = chunks.get(i);
            String prompt = promptBuilder.build(effectiveMode, safeCtx, PromptTemplateBuilder.InputType.CHUNK, body);
            String out = callSummarizeTool(prompt, effectiveMode, chunkSummaryMaxChars);
            String cleaned = SummaryOutputCleaner.clean(out);
            chunkSummaries.add(cleaned);
        }

        // 2) 병합 요약(최종)
        String mergedBody = chunkSummaries.stream()
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n\n"));

        String mergedPrompt = promptBuilder.build(effectiveMode, safeCtx, PromptTemplateBuilder.InputType.MERGED, mergedBody);
        String mergedOut = callSummarizeTool(mergedPrompt, effectiveMode, mergedSummaryMaxChars);
        String mergedCleaned = SummaryOutputCleaner.clean(mergedOut);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("chunkCount", chunks.size());
        meta.put("effectiveChunks", chunkSummaries.size());
        meta.put("mode", effectiveMode);
        meta.put("maxChunks", maxChunks);

        return new Result(mergedCleaned, meta);
    }

    /**
     * mode 결정:
     * - 기본: abstract
     * - mode가 명시되면 그대로(final/abstract)
     * - ✅ auto-final.enabled=true이고, URL/FILE + 입력이 길면(final로 승격)
     */
    private String resolveMode(String mode, SummarizeMetadataContext ctx, String body) {
        String normalized = normalizeMode(mode);

        // 호출자가 mode를 명시했으면 그대로
        if (mode != null && !mode.isBlank()) return normalized;

        // 자동 승격 옵션
        if (!autoFinalEnabled) return normalized;

        SummarizeMetadataContext.SourceType st = (ctx == null) ? SummarizeMetadataContext.SourceType.TEXT : ctx.sourceType();
        if (st == SummarizeMetadataContext.SourceType.URL || st == SummarizeMetadataContext.SourceType.FILE) {
            int len = (body == null) ? 0 : body.length();
            if (len >= autoFinalThresholdChars) {
                return "final";
            }
        }
        return normalized;
    }

    private String normalizeMode(String mode) {
        if (mode == null) return "abstract";
        String m = mode.trim().toLowerCase(Locale.ROOT);
        return "final".equals(m) ? "final" : "abstract";
    }

    /**
     * MCP tool 호출
     * - 프롬프트 빌드(promptBuilder에서 이미 완료)
     * - callTool 결과에서 text만 안전 추출
     */
    private String callSummarizeTool(String prompt, String mode, int maxChars) {
        Map<String, Object> args = new LinkedHashMap<>();
        // Node MCP summarize_document는 "text" 인자를 받는다고 가정(현재 프로젝트 구조와 일치)
        args.put("text", prompt);

        // ✅ mode는 항상 전달 (Node가 optional로 받더라도 무해)
        args.put("mode", mode);

        // Node tool이 maxChars를 지원하면 사용, 아니면 무시됨(무해)
        args.put("maxChars", maxChars);

        Object out = mcpRelayService.callTool(McpToolName.SUMMARIZE_DOCUMENT, args);

        // ✅ CallToolResult[...] 같은 toString 덤프 금지: "text"만 추출
        String extracted = extractToolText(out);

        // 혹시 extracted가 프롬프트/마커를 그대로 되돌려도 clean에서 제거
        return extracted == null ? "" : extracted;
    }

    /**
     * tool 응답에서 text 안전 추출
     */
    @SuppressWarnings("unchecked")
    private String extractToolText(Object out) {
        if (out == null) return null;

        // 1) Map 형태: { content: [ { type: "text", text: "..." } ] }
        if (out instanceof Map<?, ?> m) {
            Object result = m.get("result"); // 혹시 wrapper가 있을 수 있음
            if (result instanceof Map<?, ?> rm) {
                String t = extractFromResultMap(rm);
                if (t != null) return t;
            }
            String t = extractFromResultMap((Map<?, ?>) m);
            if (t != null) return t;
        }

        // 2) 객체 형태(CallToolResult 등): reflection으로 content 접근
        try {
            Object content = out.getClass().getMethod("content").invoke(out);
            String t = extractFromContent(content);
            if (t != null) return t;
        } catch (Exception ignored) {}

        // 마지막 fallback: toString은 프롬프트/구조가 섞이기 쉬워서 최소화
        return String.valueOf(out);
    }

    private String extractFromResultMap(Map<?, ?> m) {
        Object content = m.get("content");
        return extractFromContent(content);
    }

    private String extractFromContent(Object content) {
        if (content == null) return null;

        if (content instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first == null) return null;

            // content item이 Map이면 text 키
            if (first instanceof Map<?, ?> cm) {
                Object txt = cm.get("text");
                if (txt != null) return String.valueOf(txt);
            }

            // content item이 객체면 text() 메서드
            try {
                Object txt = first.getClass().getMethod("text").invoke(first);
                if (txt != null) return String.valueOf(txt);
            } catch (Exception ignored) {}
        }

        if (content instanceof Map<?, ?> cm) {
            Object txt = cm.get("text");
            if (txt != null) return String.valueOf(txt);
        }

        return null;
    }
}
