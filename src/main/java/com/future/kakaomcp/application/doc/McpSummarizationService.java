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
 */
@Service
public class McpSummarizationService {

    private final McpRelayService mcpRelayService;
    private final ChunkingService chunkingService;
    private final PromptTemplateBuilder promptBuilder;

    private final int finalMaxChars;
    private final int chunkSummaryMaxChars;
    private final int maxChunks;

    public McpSummarizationService(
            McpRelayService mcpRelayService,
            ChunkingService chunkingService,
            PromptTemplateBuilder promptBuilder,
            @Value("${app.summarize.final.max-chars:6000}") int finalMaxChars,
            @Value("${app.summarize.chunk.summary-max-chars:1800}") int chunkSummaryMaxChars,
            @Value("${app.summarize.chunk.max-chunks:18}") int maxChunks
    ) {
        this.mcpRelayService = mcpRelayService;
        this.chunkingService = chunkingService;
        this.promptBuilder = promptBuilder;

        this.finalMaxChars = Math.max(300, finalMaxChars);
        this.chunkSummaryMaxChars = Math.max(200, chunkSummaryMaxChars);
        this.maxChunks = Math.max(1, maxChunks);
    }

    public record Result(String summaryText, Map<String, Object> meta) {}

    public Result summarize(String text, String mode, SummarizeMetadataContext ctx) {
        String safeText = (text == null) ? "" : text.trim();
        if (safeText.isBlank()) {
            return new Result("", Map.of("chunkCount", 0));
        }

        String safeMode = normalizeMode(mode);
        SummarizeMetadataContext safeCtx = (ctx == null) ? SummarizeMetadataContext.empty() : ctx;

        // ✅ chunking은 "본문"만 (프롬프트를 섞지 않음)
        List<String> chunks = chunkingService.chunk(safeText);
        if (chunks == null || chunks.isEmpty()) {
            // chunking이 비정상일 때 fallback: 그냥 한 덩어리로
            chunks = List.of(safeText);
        }

        int originalChunkCount = chunks.size();
        if (chunks.size() > maxChunks) {
            chunks = chunks.subList(0, maxChunks);
        }

        // chunk 1개면 바로 final 요약
        if (chunks.size() == 1) {
            String out = callSummarizeTool(
                    chunks.get(0),
                    safeMode,
                    safeCtx,
                    finalMaxChars,
                    PromptTemplateBuilder.InputType.TEXT
            );
            out = SummaryOutputCleaner.clean(out);
            return new Result(out, meta(originalChunkCount, chunks.size(), 1));
        }

        // 1) 각 chunk를 abstract로 요약(merge 단계 입력으로 사용)
        List<String> chunkSummaries = new ArrayList<>();
        for (String c : chunks) {
            String s = callSummarizeTool(
                    c,
                    "abstract",
                    safeCtx,
                    chunkSummaryMaxChars,
                    PromptTemplateBuilder.InputType.CHUNK
            );
            s = SummaryOutputCleaner.clean(s);
            if (!s.isBlank()) chunkSummaries.add(s);
        }

        String merged = chunkSummaries.stream()
                .filter(x -> x != null && !x.isBlank())
                .collect(Collectors.joining("\n\n"));

        // merge된게 비면 fallback (거의 없지만 방어)
        if (merged.isBlank()) {
            String out = callSummarizeTool(
                    safeText,
                    safeMode,
                    safeCtx,
                    finalMaxChars,
                    PromptTemplateBuilder.InputType.TEXT
            );
            out = SummaryOutputCleaner.clean(out);
            return new Result(out, meta(originalChunkCount, chunks.size(), chunkSummaries.size()));
        }

        // 2) merge를 최종 mode로 요약
        String finalOut = callSummarizeTool(
                merged,
                safeMode,
                safeCtx,
                finalMaxChars,
                PromptTemplateBuilder.InputType.MERGED
        );
        finalOut = SummaryOutputCleaner.clean(finalOut);

        return new Result(finalOut, meta(originalChunkCount, chunks.size(), chunkSummaries.size()));
    }

    private Map<String, Object> meta(int originalChunkCount, int usedChunks, int chunkSummaryCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("originalChunkCount", originalChunkCount);
        m.put("usedChunkCount", usedChunks);
        m.put("maxChunks", maxChunks);
        m.put("chunkSummaryCount", chunkSummaryCount);
        m.put("finalMaxChars", finalMaxChars);
        m.put("chunkSummaryMaxChars", chunkSummaryMaxChars);
        return m;
    }

    private String normalizeMode(String mode) {
        if (mode == null) return "abstract";
        String m = mode.trim().toLowerCase(Locale.ROOT);
        return "final".equals(m) ? "final" : "abstract";
    }

    /**
     * MCP tool 호출
     * - 프롬프트 빌드(promptBuilder)
     * - callTool 결과에서 text만 안전 추출
     */
    private String callSummarizeTool(
            String bodyText,
            String mode,
            SummarizeMetadataContext ctx,
            int maxChars,
            PromptTemplateBuilder.InputType inputType
    ) {
        String safeBody = (bodyText == null) ? "" : bodyText.trim();
        if (safeBody.isBlank()) return "";

        // ✅ 프롬프트 생성 (Spring placeholder와 무관한 {{}} 토큰 치환은 Builder가 처리)
        String prompt = promptBuilder.build(mode, ctx, inputType, safeBody);

        Map<String, Object> args = new LinkedHashMap<>();
        // 현재 relay tool이 "text"를 입력으로 받는다고 가정 (기존 summarize_document 호환)
        args.put("text", prompt);
        args.put("mode", mode);

        // 만약 Node MCP tool이 maxChars를 지원한다면 이 값을 받아 활용 가능
        // 지원 안 해도 문제 없게 "optional"로 넣음
        args.put("maxChars", maxChars);

        Object out = mcpRelayService.callTool(McpToolName.SUMMARIZE_DOCUMENT, args);

        // ✅ CallToolResult[...] 같은 toString 덤프 금지: "text"만 추출
        String extracted = extractToolText(out);

        // 혹시 extracted가 프롬프트/마커를 그대로 되돌려도 clean에서 제거됨(2중 방어)
        return extracted == null ? "" : extracted;
    }

    // --------------------------
    // MCP 응답에서 "text"만 안전 추출
    // --------------------------
    @SuppressWarnings("unchecked")
    private String extractToolText(Object out) {
        if (out == null) return "";

        // 1) 이미 String이면 그대로
        if (out instanceof String s) return s;

        // 2) Map 형태: { content: [ { type:"text", text:"..." }, ... ] }
        if (out instanceof Map<?, ?> m) {
            Object content = m.get("content");
            String t = extractFromContent(content);
            if (t != null) return t;

            // 일부 구현은 result/content 구조로 감싸기도 함
            Object result = m.get("result");
            if (result instanceof Map<?, ?> rm) {
                Object rc = rm.get("content");
                t = extractFromContent(rc);
                if (t != null) return t;
            }
        }

        // 3) SDK CallToolResult: getContent()/content()
        try {
            Object content = out.getClass().getMethod("getContent").invoke(out);
            String t = extractFromContent(content);
            if (t != null) return t;
        } catch (Exception ignored) {}

        try {
            Object content = out.getClass().getMethod("content").invoke(out);
            String t = extractFromContent(content);
            if (t != null) return t;
        } catch (Exception ignored) {}

        // 4) content item getText()/text()
        try {
            Object text = out.getClass().getMethod("getText").invoke(out);
            if (text != null) return String.valueOf(text);
        } catch (Exception ignored) {}

        try {
            Object text = out.getClass().getMethod("text").invoke(out);
            if (text != null) return String.valueOf(text);
        } catch (Exception ignored) {}

        // 5) 마지막 fallback: toString은 위험하지만, 완전히 빈값이면 최소 반환
        String s = String.valueOf(out);
        return s == null ? "" : s;
    }

    private String extractFromContent(Object content) {
        if (content == null) return null;

        if (content instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);

            // Map item: {text: "..."}
            if (first instanceof Map<?, ?> fm) {
                Object txt = fm.get("text");
                if (txt != null) return String.valueOf(txt);
            }

            // item.getText()
            try {
                Object txt = first.getClass().getMethod("getText").invoke(first);
                if (txt != null) return String.valueOf(txt);
            } catch (Exception ignored) {}

            // item.text()
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
