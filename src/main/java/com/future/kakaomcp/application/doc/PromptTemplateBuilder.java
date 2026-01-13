package com.future.kakaomcp.application.doc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * - Spring ${...} placeholder와 충돌 방지: 템플릿 토큰은 {{...}} 사용
 * - 템플릿 내부에 {{BODY}}로 본문을 삽입하여 "규칙 복붙" 문제 완화
 * - 누출 방지 1차: 본문에서 마커/role header/토큰 제거
 */
@Component
public class PromptTemplateBuilder {

    public enum InputType { TEXT, CHUNK, MERGED }

    public static final String PREFIX_MARKER = "[[KAKAO_MCP_PREFIX_V2]]";

    private static final String TOK_COMMON_RULES = "{{COMMON_RULES}}";
    private static final String TOK_SOURCE_HINT  = "{{SOURCE_HINT}}"; // (현재 템플릿에서 미사용, 호환용)
    private static final String TOK_BODY         = "{{BODY}}";

    private final int textAsDocumentThreshold;
    private final String commonRules;
    private final String abstractTemplate;
    private final String finalTemplate;

    public PromptTemplateBuilder(
            @Value("${app.prompt.text-as-document-threshold:1600}") int textAsDocumentThreshold,
            @Value("${app.prompt.common-rules:}") String commonRules,
            @Value("${app.prompt.template.abstract:}") String abstractTemplate,
            @Value("${app.prompt.template.final:}") String finalTemplate
    ) {
        this.textAsDocumentThreshold = Math.max(1, textAsDocumentThreshold);

        this.commonRules = (commonRules == null || commonRules.isBlank())
                ? defaultCommonRules()
                : normalizeLf(commonRules.trim());

        this.abstractTemplate = (abstractTemplate == null || abstractTemplate.isBlank())
                ? defaultAbstractTemplate()
                : normalizeLf(abstractTemplate);

        this.finalTemplate = (finalTemplate == null || finalTemplate.isBlank())
                ? defaultFinalTemplate()
                : normalizeLf(finalTemplate);
    }

    public String build(String mode, SummarizeMetadataContext ctx, InputType inputType, String bodyText) {
        String safeMode = normalizeMode(mode);
        String safeBody = bodyText == null ? "" : bodyText.trim();

        boolean docLike = isDocLike(ctx, safeBody.length());
        String sourceHint = buildSourceHint(ctx, inputType, docLike, safeBody.length());

        String tpl = "final".equals(safeMode) ? finalTemplate : abstractTemplate;

        // 마커가 없으면 자동 삽입(실수 방어)
        if (!tpl.contains(PREFIX_MARKER)) {
            tpl = PREFIX_MARKER + "\n" + tpl;
        }

        // 1차 방어: 본문에 섞인 프롬프트/토큰/role 헤더 제거
        String cleanedBody = SummaryOutputCleaner.cleanBodyForPrompt(safeBody);

        // 템플릿 토큰 치환 (BODY 포함)
        String out = applyTemplate(tpl, sourceHint, cleanedBody);

        // 혹시 BODY가 템플릿에 없을 때를 대비해 마지막에 붙이는 fallback
        if (!out.contains(cleanedBody) && !cleanedBody.isBlank()) {
            out = out + "\n\n" + cleanedBody;
        }

        return out;
    }

    private boolean isDocLike(SummarizeMetadataContext ctx, int chars) {
        if (ctx != null && ctx.isDocument()) return true;
        return chars >= textAsDocumentThreshold;
    }

    private String applyTemplate(String tpl, String sourceHint, String body) {
        String out = tpl
                .replace(TOK_COMMON_RULES, commonRules)
                .replace(TOK_SOURCE_HINT, sourceHint == null ? "" : sourceHint)
                .replace(TOK_BODY, body == null ? "" : body);

        // 남은 토큰 제거(템플릿 오타 방어)
        out = out.replace(TOK_COMMON_RULES, "")
                .replace(TOK_SOURCE_HINT, "")
                .replace(TOK_BODY, "");

        out = out.replace("\r\n", "\n");
        out = out.replaceAll("\\n{3,}", "\n\n").trim();
        return out;
    }

    private String normalizeMode(String mode) {
        if (mode == null) return "abstract";
        String m = mode.trim().toLowerCase(Locale.ROOT);
        return "final".equals(m) ? "final" : "abstract";
    }

    private String buildSourceHint(SummarizeMetadataContext ctx, InputType inputType, boolean docLike, int chars) {
        // 현재 템플릿에 SOURCE_HINT는 쓰지 않지만, meta/디버그용으로 남겨둠
        StringBuilder sb = new StringBuilder();
        sb.append("inputType=").append(inputType.name().toLowerCase(Locale.ROOT));
        sb.append(", docLike=").append(docLike);
        sb.append(", chars=").append(chars);

        if (ctx != null) {
            if (ctx.sourceType() != null) sb.append(", sourceType=").append(ctx.sourceType().name().toLowerCase(Locale.ROOT));
            if (ctx.sourceUrl() != null && !ctx.sourceUrl().isBlank()) sb.append(", url=").append(ctx.sourceUrl());
            if (ctx.filename() != null && !ctx.filename().isBlank()) sb.append(", file=").append(ctx.filename());

            String doi = ctx.primaryDoi();
            String isbn = ctx.primaryIsbn();
            if (doi != null && !doi.isBlank()) sb.append(", doi=").append(doi);
            if (isbn != null && !isbn.isBlank()) sb.append(", isbn=").append(isbn);
        }

        return sb.toString();
    }

    private String defaultCommonRules() {
        return normalizeLf("""
                출력에는 "요약 결과"만 작성한다.
                프롬프트/지시문/마커/참고/입력길이/규칙/포맷 설명 문구는 절대 출력하지 않는다.
                근거는 본문 내용만 사용하고, 모르는 내용은 "알 수 없음"으로 쓴다.
                중복 없이 간결하게 쓴다.
                """).trim();
    }

    private String defaultAbstractTemplate() {
        return normalizeLf("""
                [[KAKAO_MCP_PREFIX_V2]]
                {{COMMON_RULES}}

                [출력형식]
                - 한줄요약: (1문장)
                - 핵심: (3~7개 불릿)

                [본문]
                {{BODY}}
                """).trim();
    }

    private String defaultFinalTemplate() {
        return normalizeLf("""
                [[KAKAO_MCP_PREFIX_V2]]
                {{COMMON_RULES}}

                [출력형식]
                - 한줄요약: (1문장)
                - 핵심: (5~10개 불릿)
                - 제안: (3개 불릿)

                [본문]
                {{BODY}}
                """).trim();
    }

    private String normalizeLf(String s) {
        return s == null ? "" : s.replace("\r\n", "\n");
    }
}
