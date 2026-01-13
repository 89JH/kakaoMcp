package com.future.kakaomcp.application.doc;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * - 모델 응답에서 프롬프트/마커/디버그/규칙 누출을 제거
 * - 최후 방어: 규칙성 문장이 남으면 앞부분 잘라내기
 */
public final class SummaryOutputCleaner {

    private SummaryOutputCleaner() {}

    // 마커
    private static final Pattern MARKER = Pattern.compile("\\[\\[KAKAO_MCP_PREFIX_[^\\]]+\\]\\]");
    // CallToolResult[...] 같은 덤프
    private static final Pattern CALL_TOOL_RESULT_LINE = Pattern.compile("(?im)^\\s*CallToolResult\\s*\\[.*$");
    // role header/system-like lines
    private static final Pattern ROLE_HEADERS = Pattern.compile("(?im)^\\s*(system|assistant|user)\\s*:\\s*.*$");
    // SOURCE_HINT 같은 내부 힌트 라인(실수로 출력되면 제거)
    private static final Pattern SOURCE_HINT_LINE = Pattern.compile("(?im)^\\s*-\\s*SOURCE\\s*:\\s*.*$");

    // 템플릿 토큰(혹시 남아있으면 제거)
    private static final Pattern TEMPLATE_TOKENS = Pattern.compile("\\{\\{\\s*(COMMON_RULES|SOURCE_HINT|BODY)\\s*\\}\\}");

    // ✅ "규칙 문구"가 그대로 출력되는 케이스 제거(한국어)
    private static final Pattern KOREAN_RULE_LEAK_LINES = Pattern.compile(
            "(?im)^\\s*-\\s*(요약\\s*결과만|프롬프트|지시문|마커|중간\\s*과정|생각|참고|입력\\s*길이|본문을\\s*근거|중복을\\s*줄이|간결하게).*?$"
    );

    // ✅ 출력형식/본문 헤더가 결과에 남으면 제거(원하면 유지해도 되지만 누출 방지에 유리)
    private static final Pattern FORMAT_HEADERS = Pattern.compile("(?im)^\\s*\\[(출력형식|본문)\\]\\s*$");

    /** 모델 응답(최종 출력) 정리 */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.replace("\r\n", "\n").trim();
        if (s.isBlank()) return "";

        // 1) 마커 제거
        s = MARKER.matcher(s).replaceAll("");

        // 2) CallToolResult 덤프 제거
        s = CALL_TOOL_RESULT_LINE.matcher(s).replaceAll("");

        // 3) role headers 제거
        s = ROLE_HEADERS.matcher(s).replaceAll("");

        // 4) SOURCE 힌트 라인 제거
        s = SOURCE_HINT_LINE.matcher(s).replaceAll("");

        // 5) 템플릿 토큰 제거
        s = TEMPLATE_TOKENS.matcher(s).replaceAll("");

        // 6) 규칙 누출 라인 제거(핵심 포인트에 그대로 나오는 케이스 방어)
        s = KOREAN_RULE_LEAK_LINES.matcher(s).replaceAll("");

        // 7) 포맷 헤더 제거
        s = FORMAT_HEADERS.matcher(s).replaceAll("");

        // 8) 공백 정리
        s = s.replaceAll("\n{3,}", "\n\n").trim();

        // 9) 여전히 규칙성 문장이 남으면 앞부분 잘라내기(보수적 방어)
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("프롬프트") || lower.contains("입력 길이") || lower.contains("요약 결과만")) {
            int idx = s.indexOf("\n\n");
            if (idx > 0 && idx < 800) {
                s = s.substring(idx + 2).trim();
            }
        }

        return s;
    }

    /** 프롬프트 입력(본문)에 섞여 들어온 “마커/토큰/role 헤더” 제거 */
    public static String cleanBodyForPrompt(String body) {
        if (body == null) return "";
        String s = body.replace("\r\n", "\n");

        s = MARKER.matcher(s).replaceAll("");
        s = TEMPLATE_TOKENS.matcher(s).replaceAll("");
        s = ROLE_HEADERS.matcher(s).replaceAll("");

        // 본문 자체에 규칙 문장이 섞여 들어오는 경우 최소 제거
        s = KOREAN_RULE_LEAK_LINES.matcher(s).replaceAll("");
        s = FORMAT_HEADERS.matcher(s).replaceAll("");

        return s.replaceAll("\n{3,}", "\n\n").trim();
    }
}
