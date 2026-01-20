package com.future.kakaomcp.application.doc;

import com.future.kakaomcp.api.mcp.McpToolProperties;
import org.springframework.stereotype.Component;

/**
 * 프롬프트 템플릿 빌더
 * - 입력 길이에 따라 핵심 bullet(5~20) 자동 증가
 * - mode/ctx/inputType에 따라 source hint 부여
 *
 * ⚠️ McpSummarizationService가 호출하는 시그니처를 그대로 유지해야 함
 */
@Component
public class PromptTemplateBuilder {

    private static final String MARKER = "[[KAKAO_MCP_PROMPT_V2]]";
    private static final String TOK_TEXT = "{{TEXT}}";
    private static final String TOK_POINTS = "{{POINTS}}";
    private static final String TOK_SOURCE_HINT = "{{SOURCE_HINT}}";

    public enum InputType {
        TEXT, URL, FILE, CHUNK, MERGED
    }

    private final String commonRules;
    private final String abstractTemplate;
    private final String finalTemplate;

    private final int tier1MaxChars;
    private final int tier2MaxChars;
    private final int tier3MaxChars;
    private final int tier4MaxChars;
    private final int tier5MaxChars;

    private final int bulletsTier1;
    private final int bulletsTier2;
    private final int bulletsTier3;
    private final int bulletsTier4;
    private final int bulletsTier5;

    private final int finalExtraBullets;
    private final int chunkBulletCap;

    public PromptTemplateBuilder(McpToolProperties toolProps) {
        // prompt
        this.commonRules = safe(toolProps.getPrompt().getCommonRules());
        this.abstractTemplate = safe(toolProps.getPrompt().getTemplate().getAbstractTemplate());
        this.finalTemplate = safe(toolProps.getPrompt().getTemplate().getFinalTemplate());

        // auto bullets
        var ab = toolProps.getSummarize().getAutoBullets();
        this.tier1MaxChars = Math.max(200, ab.getTier1MaxChars());
        this.tier2MaxChars = Math.max(this.tier1MaxChars + 1, ab.getTier2MaxChars());
        this.tier3MaxChars = Math.max(this.tier2MaxChars + 1, ab.getTier3MaxChars());
        this.tier4MaxChars = Math.max(this.tier3MaxChars + 1, ab.getTier4MaxChars());
        this.tier5MaxChars = Math.max(this.tier4MaxChars + 1, ab.getTier5MaxChars());

        // ✅ 5~20개 범위
        this.bulletsTier1 = clamp(ab.getTier1(), 5, 20);
        this.bulletsTier2 = clamp(ab.getTier2(), 5, 20);
        this.bulletsTier3 = clamp(ab.getTier3(), 5, 20);
        this.bulletsTier4 = clamp(ab.getTier4(), 5, 20);
        this.bulletsTier5 = clamp(ab.getTier5(), 5, 20);
        this.finalExtraBullets = clamp(ab.getFinalExtra(), 0, 5);
        this.chunkBulletCap = clamp(ab.getChunkCap(), 5, 20);
    }

    /**
     * ✅ McpSummarizationService에서 사용
     */
    public String build(String mode, SummarizeMetadataContext ctx, InputType inputType, String body) {
        String safeBody = body == null ? "" : body.trim();
        String safeMode = normalizeMode(mode);
        int points = decideBulletCount(safeBody.length(), safeMode, inputType);

        String sourceHint = buildSourceHint(ctx, inputType);
        String tpl = "final".equals(safeMode) ? finalTemplate : abstractTemplate;

        // marker/commonRules가 템플릿에 없으면 앞에 붙인다
        String out = tpl;
        if (!out.contains(MARKER)) {
            out = MARKER + "\n" + commonRules + "\n\n" + out;
        } else if (!commonRules.isBlank() && !out.contains(commonRules)) {
            out = commonRules + "\n\n" + out;
        }

        out = out.replace(TOK_TEXT, safeBody)
                .replace(TOK_SOURCE_HINT, sourceHint)
                .replace(TOK_POINTS, String.valueOf(points));

        return out;
    }

    /**
     * (호환) 기존 서명이 다른 곳에서 쓰일 수 있어 남겨둠
     */
    public String build(String text, String mode, InputType inputType) {
        return build(mode, SummarizeMetadataContext.empty(), inputType, text);
    }

    private String buildSourceHint(SummarizeMetadataContext ctx, InputType inputType) {
        if (inputType == InputType.CHUNK) return " (문서 일부 요약)";
        if (inputType == InputType.MERGED) return " (요약 병합)";
        if (inputType == InputType.FILE) return " (파일 요약)";
        if (inputType == InputType.URL) return " (웹페이지 요약)";

        // TEXT일 때는 ctx 기준으로 힌트 제공
        if (ctx != null) {
            if (ctx.sourceType() == SummarizeMetadataContext.SourceType.URL) return " (웹페이지 요약)";
            if (ctx.sourceType() == SummarizeMetadataContext.SourceType.FILE) return " (파일 요약)";
        }
        return "";
    }

    private int decideBulletCount(int len, String mode, InputType inputType) {
        int n;
        if (len <= tier1MaxChars) n = bulletsTier1;
        else if (len <= tier2MaxChars) n = bulletsTier2;
        else if (len <= tier3MaxChars) n = bulletsTier3;
        else if (len <= tier4MaxChars) n = bulletsTier4;
        else n = bulletsTier5;

        if ("final".equals(mode)) n = clamp(n + finalExtraBullets, 5, 20);

        if (inputType == InputType.CHUNK) {
            n = Math.min(n, chunkBulletCap);
        }

        return clamp(n, 5, 20);
    }

    private String normalizeMode(String mode) {
        String m = (mode == null) ? "" : mode.trim().toLowerCase();
        return "final".equals(m) ? "final" : "abstract";
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
