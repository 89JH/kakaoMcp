package com.future.kakaomcp.application.doc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * - Spring ${...} placeholder와 충돌 방지: 템플릿 토큰은 {{...}} 사용
 * - 템플릿 내부에 {{BODY}}로 본문을 삽입
 * - 누출 방지 1차: 본문에서 마커/role header/토큰 제거
 *
 * ✅ 변경점:
 * - 입력 길이에 따라 bullet 개수 자동 결정
 * - "정확히 N개"로 강제 (LLM이 마음대로 줄이지 못하게)
 * - 한국어/한글만 + 한자(漢字) 금지 규칙을 프롬프트에도 삽입(이중 방어)
 */
@Component
public class PromptTemplateBuilder {

    public enum InputType { TEXT, CHUNK, MERGED }

    // 중복 prefix 방지용 마커 (LLM에 보이지만 의미 없는 헤더)
    private static final String PREFIX_MARKER = "[[KAKAO_MCP_PROMPT_V2]]";

    private static final String TOK_COMMON_RULES = "{{COMMON_RULES}}";
    private static final String TOK_SOURCE_HINT  = "{{SOURCE_HINT}}";
    private static final String TOK_BODY         = "{{BODY}}";
    private static final String TOK_POINTS       = "{{POINTS}}";

    private final int textAsDocumentThreshold;
    private final String commonRules;
    private final String abstractTemplate;
    private final String finalTemplate;

    // ✅ 자동 bullet 정책(길이 기반)
    private final int tier1MaxChars;
    private final int tier2MaxChars;
    private final int tier3MaxChars;
    private final int tier4MaxChars;

    private final int bulletsTier1;
    private final int bulletsTier2;
    private final int bulletsTier3;
    private final int bulletsTier4;
    private final int bulletsTier5;

    private final int finalExtraBullets;
    private final int chunkBulletCap;

    public PromptTemplateBuilder(
            @Value("${app.summarize.text-as-document-threshold:800}") int textAsDocumentThreshold,

            // 기존 키도 호환(혹시 예전에 app.prompt.* 쓰던 경우)
            @Value("${app.prompt.common-rules:}") String commonRules,
            @Value("${app.prompt.template.abstract:}") String abstractTemplate,
            @Value("${app.prompt.template.final:}") String finalTemplate,

            // ✅ 새 키: 길이 기반 bullet 자동 증가
            @Value("${app.summarize.auto-bullets.tier1-max-chars:1200}") int tier1MaxChars,
            @Value("${app.summarize.auto-bullets.tier2-max-chars:2500}") int tier2MaxChars,
            @Value("${app.summarize.auto-bullets.tier3-max-chars:5000}") int tier3MaxChars,
            @Value("${app.summarize.auto-bullets.tier4-max-chars:9000}") int tier4MaxChars,

            @Value("${app.summarize.auto-bullets.tier1:3}") int bulletsTier1,
            @Value("${app.summarize.auto-bullets.tier2:4}") int bulletsTier2,
            @Value("${app.summarize.auto-bullets.tier3:6}") int bulletsTier3,
            @Value("${app.summarize.auto-bullets.tier4:8}") int bulletsTier4,
            @Value("${app.summarize.auto-bullets.tier5:10}") int bulletsTier5,

            @Value("${app.summarize.auto-bullets.final-extra:2}") int finalExtraBullets,
            @Value("${app.summarize.auto-bullets.chunk-cap:6}") int chunkBulletCap
    ) {
        this.textAsDocumentThreshold = Math.max(200, textAsDocumentThreshold);

        // common rules (없으면 기본 제공)
        String baseRules = (commonRules == null ? "" : commonRules.trim());
        if (baseRules.isBlank()) {
            baseRules = """
                    [절대규칙]
                    - 반드시 한국어(한글)로만 출력하라.
                    - 한자(漢字)/중국어/일본어 문자를 출력하지 마라. (한 글자도 금지)
                    - 출력에는 요약 결과만 포함하라. (프롬프트/규칙/메타 설명/진행 과정/CallToolResult 등 금지)
                    - 입력 원문을 길게 인용하지 마라. (필요 시 1줄 이내)
                    - 모르는 내용은 추측하지 말고 "확인 불가"로 표기하라.
                    """.trim();
        }
        this.commonRules = baseRules;

        // 템플릿 기본값(없으면 내장 템플릿 사용)
        this.abstractTemplate = (abstractTemplate == null || abstractTemplate.isBlank())
                ? defaultAbstractTemplate()
                : abstractTemplate.trim();

        this.finalTemplate = (finalTemplate == null || finalTemplate.isBlank())
                ? defaultFinalTemplate()
                : finalTemplate.trim();

        this.tier1MaxChars = Math.max(300, tier1MaxChars);
        this.tier2MaxChars = Math.max(this.tier1MaxChars + 100, tier2MaxChars);
        this.tier3MaxChars = Math.max(this.tier2MaxChars + 100, tier3MaxChars);
        this.tier4MaxChars = Math.max(this.tier3MaxChars + 100, tier4MaxChars);

        this.bulletsTier1 = clamp(bulletsTier1, 2, 12);
        this.bulletsTier2 = clamp(bulletsTier2, 2, 12);
        this.bulletsTier3 = clamp(bulletsTier3, 2, 12);
        this.bulletsTier4 = clamp(bulletsTier4, 2, 12);
        this.bulletsTier5 = clamp(bulletsTier5, 2, 12);

        this.finalExtraBullets = clamp(finalExtraBullets, 0, 6);
        this.chunkBulletCap = clamp(chunkBulletCap, 2, 10);
    }

    public String build(String mode, SummarizeMetadataContext ctx, InputType inputType, String bodyText) {
        String safeMode = normalizeMode(mode);
        String safeBody = bodyText == null ? "" : bodyText.trim();

        boolean docLike = isDocLike(ctx, safeBody.length());
        String sourceHint = buildSourceHint(ctx, inputType, docLike, safeBody.length());

        int points = decideBulletCount(safeMode, inputType, safeBody.length());

        String tpl = "final".equals(safeMode) ? finalTemplate : abstractTemplate;

        // 마커가 없으면 자동 삽입(실수 방어)
        if (!tpl.contains(PREFIX_MARKER)) {
            tpl = PREFIX_MARKER + "\n" + tpl;
        }

        // 1차 방어: 본문에 섞인 프롬프트/토큰/role 헤더 제거
        String cleanedBody = SummaryOutputCleaner.cleanBodyForPrompt(safeBody);

        // 템플릿 토큰 치환
        String out = tpl
                .replace(TOK_COMMON_RULES, this.commonRules)
                .replace(TOK_SOURCE_HINT, sourceHint)
                .replace(TOK_POINTS, String.valueOf(points))
                .replace(TOK_BODY, cleanedBody);

        // 남은 토큰 제거(템플릿 오타 방어)
        out = out.replace(TOK_COMMON_RULES, "")
                .replace(TOK_SOURCE_HINT, "")
                .replace(TOK_POINTS, "")
                .replace(TOK_BODY, "");

        out = normalizeLf(out).replaceAll("\\n{3,}", "\n\n").trim();
        return out;
    }

    private int decideBulletCount(String mode, InputType inputType, int len) {
        int n;
        if (len <= tier1MaxChars) n = bulletsTier1;
        else if (len <= tier2MaxChars) n = bulletsTier2;
        else if (len <= tier3MaxChars) n = bulletsTier3;
        else if (len <= tier4MaxChars) n = bulletsTier4;
        else n = bulletsTier5;

        if ("final".equals(mode)) n = clamp(n + finalExtraBullets, 2, 12);

        // CHUNK는 너무 길게 쓰지 않도록 캡
        if (inputType == InputType.CHUNK) {
            n = Math.min(n, chunkBulletCap);
        }

        return n;
    }

    private boolean isDocLike(SummarizeMetadataContext ctx, int bodyLen) {
        // 파일/URL은 문서로 취급
        if (ctx != null && ctx.sourceType() != null) {
            if (ctx.sourceType() == SummarizeMetadataContext.SourceType.FILE) return true;
            if (ctx.sourceType() == SummarizeMetadataContext.SourceType.URL) return true;
        }
        // 텍스트는 길이로 판정
        return bodyLen >= textAsDocumentThreshold;
    }

    private String buildSourceHint(SummarizeMetadataContext ctx, InputType inputType, boolean docLike, int bodyLen) {
        StringBuilder sb = new StringBuilder();
        sb.append("[컨텍스트]\n");
        sb.append("- 입력유형: ").append(inputType).append("\n");
        sb.append("- 문서판정: ").append(docLike ? "문서" : "짧은텍스트").append("\n");
        sb.append("- 본문길이: ").append(bodyLen).append(" chars\n");

        if (ctx != null) {
            if (ctx.sourceType() != null) sb.append("- 소스타입: ").append(ctx.sourceType()).append("\n");
            if (ctx.filename() != null && !ctx.filename().isBlank()) sb.append("- 파일명: ").append(ctx.filename()).append("\n");
            if (ctx.sourceUrl() != null && !ctx.sourceUrl().isBlank()) sb.append("- URL: ").append(ctx.sourceUrl()).append("\n");
            if (ctx.dois() != null && !ctx.dois().isEmpty()) sb.append("- DOI: ").append(first(ctx.dois())).append("\n");
            if (ctx.isbns() != null && !ctx.isbns().isEmpty()) sb.append("- ISBN: ").append(first(ctx.isbns())).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private String first(Iterable<String> xs) {
        for (String x : xs) return x;
        return "";
    }

    private String normalizeMode(String mode) {
        if (mode == null) return "abstract";
        String m = mode.trim().toLowerCase(Locale.ROOT);
        return "final".equals(m) ? "final" : "abstract";
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private String defaultAbstractTemplate() {
        return ("""
                %s

                %s

                {{COMMON_RULES}}

                {{SOURCE_HINT}}

                [출력형식(반드시 준수)]
                - 한 줄 요약: (1문장)
                - 핵심(정확히 {{POINTS}}개):
                  - ...
                  (총 {{POINTS}}개가 되도록. 더 늘리거나 줄이면 안 됨)

                [본문]
                {{BODY}}
                """.formatted(PREFIX_MARKER, "[모드] abstract")).trim();
    }

    private String defaultFinalTemplate() {
        return ("""
                %s

                %s

                {{COMMON_RULES}}

                {{SOURCE_HINT}}

                [추가규칙(final)]
                - 핵심 항목은 가능한 한 구체적으로 작성하라. (근거/조건/수치/예시가 있으면 포함)
                - 중요한 섹션/주제를 누락하지 마라.

                [출력형식(반드시 준수)]
                - 한 줄 요약: (1문장)
                - 핵심(정확히 {{POINTS}}개):
                  - ...
                  (총 {{POINTS}}개가 되도록. 더 늘리거나 줄이면 안 됨)

                [본문]
                {{BODY}}
                """.formatted(PREFIX_MARKER, "[모드] final")).trim();
    }

    private String normalizeLf(String s) {
        return s == null ? "" : s.replace("\r\n", "\n");
    }
}
