package com.future.kakaomcp.domain.doc;

import java.util.regex.Pattern;

/**
 * Tika 추출 텍스트를 요약 전에 정리한다.
 * - 하이픈 줄바꿈(partic-\nular) 복원
 * - 과도한 줄바꿈/공백 정리
 * - 깨진 문자(Ð 등) 최소 보정
 */
public final class TextPreprocessor {

    private TextPreprocessor() {}

    private static final Pattern HYPHEN_LINEBREAK = Pattern.compile("([A-Za-z])\\-\\s*\\R\\s*([A-Za-z])");
    private static final Pattern MANY_SPACES = Pattern.compile("[ \\t]{2,}");
    private static final Pattern MANY_NEWLINES = Pattern.compile("(\\R\\s*){3,}");

    public static String preprocessForSummarize(String text) {
        if (text == null || text.isBlank()) return "";

        String t = text;

        // 1) 하이픈으로 끊긴 단어 복원: "partic-\nular" -> "particular"
        t = HYPHEN_LINEBREAK.matcher(t).replaceAll("$1$2");

        // 2) 다중 개행 축소 (문단 구분은 유지)
        t = MANY_NEWLINES.matcher(t).replaceAll("\n\n");

        // 3) 탭/다중 공백 정리
        t = MANY_SPACES.matcher(t).replaceAll(" ");

        // 4) 흔한 깨짐 문자 최소 보정
        t = t.replace('Ð', '-');

        return t.trim();
    }
}
