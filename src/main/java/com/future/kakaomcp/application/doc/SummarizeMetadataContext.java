package com.future.kakaomcp.application.doc;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 요약 후처리(불릿 정제)를 위한 메타데이터 컨텍스트.
 * - author/email/doi/isbn/url/affiliation 등의 "잡음"을 필터링할 때 사용
 */
public record SummarizeMetadataContext(
        Set<String> authors,        // "Walter Sinnott-Armstrong" 같은 값들
        Set<String> emails,         // "abc@domain.com"
        Set<String> dois,           // "10.xxxx/...."
        Set<String> isbns,          // "978..." (숫자/ X 포함)
        Set<String> affiliations    // "University of Maryland" 등
) {
    public static SummarizeMetadataContext empty() {
        return new SummarizeMetadataContext(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    public SummarizeMetadataContext {
        authors = (authors == null) ? Set.of() : normalizeSet(authors);
        emails = (emails == null) ? Set.of() : normalizeSet(emails);
        dois = (dois == null) ? Set.of() : normalizeSet(dois);
        isbns = (isbns == null) ? Set.of() : normalizeSet(isbns);
        affiliations = (affiliations == null) ? Set.of() : normalizeSet(affiliations);
    }

    private static Set<String> normalizeSet(Set<String> in) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return Collections.unmodifiableSet(out);
    }

    // 기본 이메일 패턴 (후처리용)
    public static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    // DOI로 보이는 패턴 (후처리용)
    public static final Pattern DOI_PATTERN =
            Pattern.compile("\\b10\\.\\d{4,9}/[^\\s\"'<>]+\\b", Pattern.CASE_INSENSITIVE);

    // ISBN 후보(숫자/하이픈/공백 + X) (후처리용)
    public static final Pattern ISBN_CANDIDATE =
            Pattern.compile("\\b(?:97[89][\\-\\s]?)?(?:\\d[\\-\\s]?){9,12}[\\dXx]\\b");
}
