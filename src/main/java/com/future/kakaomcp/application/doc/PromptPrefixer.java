package com.future.kakaomcp.application.doc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PromptPrefixer {

    // 중복 prefix 방지용 마커 (LLM에 보이지만 의미 없는 헤더)
    private static final String PREFIX_MARKER = "[[KAKAO_MCP_PREFIX_V1]]";

    // 문서로 간주하는 텍스트 길이 임계치 (summarize_text에서도 이 이상이면 문서요약 prefix 적용)
    private final int textAsDocumentThreshold;

    // 요약 길이(문장수) 기본값
    private final int abstractSentencesDefault;
    private final int finalSentencesDefault;

    // 핵심 포인트 개수 기본값
    private final int abstractPointsDefault;
    private final int finalPointsDefault;

    public PromptPrefixer(
            @Value("${app.summarize.text-as-document-threshold:800}") int textAsDocumentThreshold,
            @Value("${app.summarize.length.abstract.sentences:10}") int abstractSentencesDefault,
            @Value("${app.summarize.length.final.sentences:20}") int finalSentencesDefault,
            @Value("${app.summarize.length.abstract.points:8}") int abstractPointsDefault,
            @Value("${app.summarize.length.final.points:15}") int finalPointsDefault
    ) {
        this.textAsDocumentThreshold = Math.max(200, textAsDocumentThreshold);
        this.abstractSentencesDefault = clamp(abstractSentencesDefault, 3, 30);
        this.finalSentencesDefault = clamp(finalSentencesDefault, 5, 60);
        this.abstractPointsDefault = clamp(abstractPointsDefault, 3, 20);
        this.finalPointsDefault = clamp(finalPointsDefault, 5, 30);
    }

    public boolean alreadyPrefixed(String text) {
        if (text == null) return false;
        return text.contains(PREFIX_MARKER);
    }

    public boolean shouldApplyDocumentPrefix(SummarizeMetadataContext ctx, int inputChars) {
        if (ctx != null && ctx.isDocument()) return true;
        return inputChars >= textAsDocumentThreshold; // summarize_text라도 길면 문서로 취급
    }

    /**
     * 문서(웹/파일/긴 텍스트) 전용 prefix
     * - "아래 본문만 근거로" 요약하도록 강제
     * - 문장/포인트 수는 mode에 따라 다르게
     */
    public String buildDocumentPrefix(String mode, int inputChars, String sourceHint) {
        String m = (mode == null || mode.isBlank()) ? "abstract" : mode.trim().toLowerCase();

        int sentences = m.equals("final") ? finalSentencesDefault : abstractSentencesDefault;
        int points = m.equals("final") ? finalPointsDefault : abstractPointsDefault;

        // 입력이 매우 짧으면(문서인데도 본문이 적은 경우) 너무 길게 요구하지 않도록 자동 조절
        if (inputChars < 1200) {
            sentences = Math.min(sentences, 8);
            points = Math.min(points, 6);
        }

        String hint = (sourceHint == null || sourceHint.isBlank()) ? "" : ("- source: " + sourceHint + "\n");

        return """
                %s
                [역할] 너는 '문서 요약 편집자'다. 아래 [본문]만 근거로 요약한다. (추측/창작 금지)
                [공통 규칙]
                - 출력 언어: 한국어
                - 같은 의미 반복 금지
                - 광고/쿠키/메뉴/목차/네비게이션/댓글/공유버튼/각주/참고문헌 목록은 내용에서 제외
                - 가능하면 핵심 용어(고유명사/프로토콜/기술명)는 유지

                [요약 조건]
                - 전체 요약: 최대 %d문장
                - 핵심 포인트: %d개 (각 포인트는 1문장, 서로 다른 관점/정보로 작성)

                %s[본문]
                """.formatted(PREFIX_MARKER, sentences, points, hint);
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
