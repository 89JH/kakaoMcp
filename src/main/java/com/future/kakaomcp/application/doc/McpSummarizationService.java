package com.future.kakaomcp.application.doc;

import com.future.kakaomcp.domain.doc.ChunkingService;
import com.future.kakaomcp.infrastructure.mcp.McpRelayService;
import com.future.kakaomcp.infrastructure.mcp.McpToolName;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * "운영 중" 요약 로직 실행부.
 *
 * - 실제 요약 생성은 MCP tool(summarize_document)에서 수행.
 * - Java는 chunk -> map(부분요약) -> sanitize(불릿 정제) -> reduce(최종) 파이프라인을 담당.
 */
@Service
public class McpSummarizationService {

    private final McpRelayService mcpRelayService;
    private final ChunkingService chunkingService;

    private final int finalMaxChars;
    private final int chunkSummaryMaxChars;

    public McpSummarizationService(
            McpRelayService mcpRelayService,
            ChunkingService chunkingService,
            @Value("${app.summarize.final.max-chars:6000}") int finalMaxChars,
            @Value("${app.summarize.chunk.summary-max-chars:1800}") int chunkSummaryMaxChars
    ) {
        this.mcpRelayService = mcpRelayService;
        this.chunkingService = chunkingService;
        this.finalMaxChars = Math.max(1200, finalMaxChars);
        this.chunkSummaryMaxChars = Math.max(600, chunkSummaryMaxChars);
    }

    /** 기존 호환(컨텍스트 없음) */
    public Result summarize(String text, String mode) {
        return summarize(text, mode, SummarizeMetadataContext.empty());
    }

    /**
     * 메타데이터 컨텍스트를 받아 reduce 전 bullet 정제(잡음 제거)를 수행한다.
     */
    public Result summarize(String text, String mode, SummarizeMetadataContext ctx) {
        String safeText = (text == null) ? "" : text;
        String safeMode = (mode == null || mode.isBlank()) ? "abstract" : mode.trim();
        SummarizeMetadataContext safeCtx = (ctx == null) ? SummarizeMetadataContext.empty() : ctx;

        List<String> chunks = chunkingService.chunk(safeText);
        if (chunks.isEmpty()) {
            return new Result("", Map.of("chunkCount", 0));
        }

        // chunk 1개면 바로 최종 요약
        if (chunks.size() == 1) {
            String summary = callSummarizeTool(chunks.get(0), safeMode, finalMaxChars);
            return new Result(summary, Map.of("chunkCount", 1));
        }

        // map: chunk별 부분 요약(abstract)
        List<String> partialSummaries = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            partialSummaries.add(callSummarizeTool(chunk, "abstract", chunkSummaryMaxChars));
        }

        // reduce input sanitize: 헤더 제거 + bullet 추출 + 메타 잡음 제거 + 쪼개진 bullet 합치기 + 중복 제거
        List<String> sanitizedPartials = partialSummaries.stream()
                .map(p -> sanitizePartial(p, safeCtx))
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toList());

        String reduceInput = String.join("\n\n---\n\n", sanitizedPartials);
        String finalSummary = callSummarizeTool(reduceInput, safeMode, finalMaxChars);

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("chunkCount", chunks.size());
        debug.put("partialCount", partialSummaries.size());
        debug.put("sanitizedPartialCount", sanitizedPartials.size());
        debug.put("partialPreview", previewPartials(partialSummaries));
        debug.put("sanitizedPreview", previewPartials(sanitizedPartials));

        return new Result(finalSummary, debug);
    }

    /**
     * 부분요약 텍스트에서 reduce에 넣을 “의미 있는 bullet”만 남긴다.
     * 제거 대상:
     * - author / email / doi / isbn / url / affiliation(University 등)
     * - 너무 짧은 라벨성 bullet
     *
     * 추가 처리:
     * - 쪼개진 bullet 합치기(문장 조각)
     * - 중복 bullet 제거
     */
    private String sanitizePartial(String partial, SummarizeMetadataContext ctx) {
        if (partial == null) return "";

        String[] lines = partial.split("\\R");
        List<String> kept = new ArrayList<>();

        for (String line : lines) {
            String s = (line == null) ? "" : line.trim();
            if (s.isEmpty()) continue;

            // markdown headings 제거
            if (s.startsWith("#")) continue;

            // "제목(추정)" 같은 라벨 제거
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.contains("제목(추정)") || lower.contains("title(estimated)") || lower.equals("요약")) {
                continue;
            }

            kept.add(s);
        }

        boolean hasBullet = kept.stream().anyMatch(this::looksLikeBullet);

        // bullet이 없으면 문장 합쳐서 반환(단, 잡음이면 제거)
        if (!hasBullet) {
            String merged = String.join(" ", kept).replaceAll("\\s+", " ").trim();
            return filterNoiseSentence(merged, ctx) ? "" : merged;
        }

        // bullet만 추출 -> prefix normalize -> 메타 잡음 제거
        List<String> bullets = kept.stream()
                .filter(this::looksLikeBullet)
                .map(this::normalizeBulletPrefix)
                .filter(b -> !isNoiseBullet(b, ctx))
                .collect(Collectors.toList());

        // 쪼개진 bullet(문장 조각) 합치기 + 중복 제거
        bullets = mergeWrappedBullets(bullets);
        bullets = dedupBullets(bullets);

        // 너무 많이 제거돼서 비면 안전장치로 원본에서 1~2개 살림
        if (bullets.isEmpty()) {
            List<String> fallback = kept.stream()
                    .filter(this::looksLikeBullet)
                    .map(this::normalizeBulletPrefix)
                    .limit(2)
                    .collect(Collectors.toList());
            return String.join("\n", fallback).trim();
        }

        return String.join("\n", bullets).trim();
    }

    /** "- ", "•", "*", "1." 등을 bullet로 본다 */
    private boolean looksLikeBullet(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.startsWith("-") || t.startsWith("•") || t.startsWith("*") || t.matches("^\\d+\\..*");
    }

    /** bullet prefix를 "- " 형태로 통일 */
    private String normalizeBulletPrefix(String s) {
        if (s == null) return "";
        String t = s.trim();

        if (t.matches("^\\d+\\..*")) {
            t = "- " + t.replaceFirst("^\\d+\\.\\s*", "");
        }

        t = t.replaceFirst("^[-•*]\\s*", "- ");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    /**
     * 메타데이터 잡음 bullet인지 판단
     */
    private boolean isNoiseBullet(String bulletLine, SummarizeMetadataContext ctx) {
        if (bulletLine == null) return true;

        String content = bulletLine.startsWith("-") ? bulletLine.substring(1).trim() : bulletLine.trim();
        if (content.isEmpty()) return true;

        // 짧은 소속 라인 제거
        if (content.length() <= 32 && looksLikeAffiliation(content)) return true;

        // email 제거
        if (SummarizeMetadataContext.EMAIL_PATTERN.matcher(content).find()) return true;
        if (containsAnyIgnoreCase(content, ctx.emails())) return true;

        // DOI/URL 제거
        if (containsUrlLike(content)) return true;
        if (SummarizeMetadataContext.DOI_PATTERN.matcher(content).find()) return true;
        if (containsAnyIgnoreCase(content, ctx.dois())) return true;

        // ISBN 제거(짧은 라벨성일 때)
        if (SummarizeMetadataContext.ISBN_CANDIDATE.matcher(content).find() && content.length() <= 32) return true;
        if (containsAnyIgnoreCase(content, ctx.isbns())) return true;

        // 저자 라인 제거(가능하면)
        if (isLikelyAuthorLine(content, ctx)) return true;

        // affiliations set과 매칭되면 제거
        if (containsAnyIgnoreCase(content, ctx.affiliations())) return true;

        // 가격/권리/ACM 라벨성 제거(짧을 때)
        String lower = content.toLowerCase(Locale.ROOT);
        if ((lower.startsWith("$") || lower.contains("copyright") || lower.contains("acm")) && content.length() <= 60) {
            return true;
        }

        return false;
    }

    /**
     * bullet이 아닌 문장 합쳐진 케이스에서 잡음 제거 판단
     */
    private boolean filterNoiseSentence(String sentence, SummarizeMetadataContext ctx) {
        if (sentence == null || sentence.isBlank()) return true;
        String s = sentence.trim();

        if (SummarizeMetadataContext.EMAIL_PATTERN.matcher(s).find()) return true;
        if (SummarizeMetadataContext.DOI_PATTERN.matcher(s).find()) return true;
        if (containsUrlLike(s)) return true;

        if (isLikelyAuthorLine(s, ctx)) return true;

        return false;
    }

    /** "http/https/doi.org" 포함이면 URL성으로 간주 */
    private boolean containsUrlLike(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("http://") || lower.contains("https://") || lower.contains("doi.org");
    }

    /** 소속/기관성 텍스트 휴리스틱 */
    private boolean looksLikeAffiliation(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("university")
                || lower.contains("institute")
                || lower.contains("department")
                || lower.contains("school")
                || lower.contains("laboratory")
                || lower.contains("lab");
    }

    /** 저자 라인처럼 보이는지(메타 기반 + 휴리스틱) */
    private boolean isLikelyAuthorLine(String content, SummarizeMetadataContext ctx) {
        if (content == null) return false;
        String t = content.trim();
        if (t.isEmpty()) return true;

        if (!ctx.authors().isEmpty() && containsAnyIgnoreCase(t, ctx.authors())) {
            if (t.length() <= 60) return true;
        }

        // 메타가 비어도 이름만 나열된 줄 제거(휴리스틱)
        if (t.length() <= 60) {
            String[] words = t.split("\\s+");
            if (words.length >= 2 && words.length <= 5) {
                int capitalStart = 0;
                for (String w : words) {
                    if (!w.isEmpty() && Character.isUpperCase(w.charAt(0))) capitalStart++;
                }
                boolean looksLikeName = capitalStart >= Math.max(2, words.length - 1);

                boolean hasVerbLike = t.toLowerCase(Locale.ROOT).matches(
                        ".*\\b(found|show|propose|evaluate|examine|investigate|measure|analyz|demonstrat)\\w*\\b.*"
                );

                boolean hasAt = t.contains("@");
                if (looksLikeName && !hasVerbLike && !hasAt && !looksLikeAffiliation(t)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean containsAnyIgnoreCase(String haystack, Set<String> needles) {
        if (haystack == null || haystack.isBlank() || needles == null || needles.isEmpty()) return false;
        String h = haystack.toLowerCase(Locale.ROOT);
        for (String n : needles) {
            if (n == null) continue;
            String nn = n.trim();
            if (nn.isEmpty()) continue;
            if (h.contains(nn.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * 쪼개진 bullet(문장 조각)을 합친다.
     * - "- Given ..." 다음 줄이 "- how ..."처럼 잘못 잘린 경우는 합치기 어렵지만,
     *   최소한 "bullet로 끊긴 조각"을 한 줄로 만드는 효과가 있다.
     */
    private List<String> mergeWrappedBullets(List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) return List.of();

        List<String> merged = new ArrayList<>();
        StringBuilder cur = null;

        for (String b : bullets) {
            if (b == null || b.isBlank()) continue;
            String t = b.trim();

            // 표준 bullet "- " 시작
            if (t.startsWith("- ")) {
                if (cur != null) merged.add(cur.toString().trim());
                cur = new StringBuilder(t);
            } else {
                // 혹시라도 bullet 표시 빠진 연속줄이면 이전에 이어붙임
                if (cur == null) cur = new StringBuilder("- " + t);
                else cur.append(" ").append(t);
            }
        }
        if (cur != null) merged.add(cur.toString().trim());

        return merged.stream()
                .map(x -> x.replaceAll("\\s+", " ").trim())
                .toList();
    }

    /**
     * bullet 중복 제거 (구두점 제거 후 비교)
     */
    private List<String> dedupBullets(List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) return List.of();

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();

        for (String b : bullets) {
            if (b == null) continue;
            String raw = b.trim();
            if (raw.isEmpty()) continue;

            String norm = raw.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9가-힣\\s]", "")
                    .replaceAll("\\s+", " ")
                    .trim();

            if (norm.length() < 12) continue;
            if (seen.add(norm)) out.add(raw);
        }
        return out;
    }

    private String callSummarizeTool(String text, String mode, int maxChars) {
        McpSchema.CallToolResult result = mcpRelayService.callTool(
                McpToolName.SUMMARIZE_DOCUMENT,
                Map.of(
                        "text", text,
                        "mode", mode,
                        "maxChars", maxChars
                )
        );
        return extractTextFromResult(result);
    }

    private String extractTextFromResult(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "";
        }
        Object first = result.content().get(0);
        if (first instanceof McpSchema.TextContent tc) {
            return tc.text() == null ? "" : tc.text();
        }
        return first == null ? "" : String.valueOf(first);
    }

    private List<String> previewPartials(List<String> partials) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(3, partials.size()); i++) {
            String s = partials.get(i);
            if (s == null) continue;
            String p = s.replaceAll("\\s+", " ").trim();
            out.add(p.length() > 220 ? p.substring(0, 220) + "..." : p);
        }
        return out;
    }

    public record Result(String summaryText, Map<String, Object> meta) {}
}
