package com.future.kakaomcp.domain.doc.metadata;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOI / ISBN 식별자 추출기.
 *
 * 목표:
 * - doi.org / doi: 형식 우선순위 반영
 * - DOI 정규화
 * - ISBN-10/13 체크섬 검증 통과한 것만 채택
 * - 결과는 "우선순위가 높은 순서"로 유지(LinkedHashSet / List)
 */
public final class DocumentIdentifierExtractor {

    private DocumentIdentifierExtractor() {}

    // DOI candidates:
    // 1) https://doi.org/<DOI>
    private static final Pattern DOI_URL =
            Pattern.compile("https?://(?:dx\\.)?doi\\.org/\\s*(10\\.\\d{4,9}/[^\\s\"'<>]+)", Pattern.CASE_INSENSITIVE);

    // 2) doi: <DOI>
    private static final Pattern DOI_PREFIX =
            Pattern.compile("\\bdoi\\s*:\\s*(10\\.\\d{4,9}/[^\\s\"'<>]+)", Pattern.CASE_INSENSITIVE);

    // 3) bare DOI
    private static final Pattern DOI_BARE =
            Pattern.compile("\\b(10\\.\\d{4,9}/[^\\s\"'<>]+)", Pattern.CASE_INSENSITIVE);

    // ISBN candidates:
    // allow hyphens/spaces, optional ISBN prefix
    private static final Pattern ISBN =
            Pattern.compile("\\b(?:ISBN(?:-1[03])?\\s*:?\\s*)?((?:97[89][\\-\\s]?)?(?:\\d[\\-\\s]?){9,12}[\\dXx])\\b");

    /**
     * 기존 호환: DOI/ISBN을 Set<String>으로 반환.
     * - DOI는 정규화된 "10.xxxx/..." 형태
     * - ISBN은 하이픈 제거한 숫자(또는 X 포함) 형태
     */
    public static Set<String> extractFromText(String text) {
        DetailedIdentifiers d = extractDetailedFromText(text);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.addAll(d.dois());
        out.addAll(d.isbns());
        return out;
    }

    /**
     * 추천: 우선순위가 적용된 상세 결과.
     */
    public static DetailedIdentifiers extractDetailedFromText(String text) {
        if (text == null || text.isBlank()) {
            return new DetailedIdentifiers(List.of(), List.of());
        }

        // DOI: 우선순위대로 수집 (URL -> doi: -> bare)
        LinkedHashSet<String> doiSet = new LinkedHashSet<>();
        collectDoi(text, DOI_URL, doiSet);
        collectDoi(text, DOI_PREFIX, doiSet);
        collectDoi(text, DOI_BARE, doiSet);

        // ISBN: 체크섬 검증 통과만
        LinkedHashSet<String> isbnSet = new LinkedHashSet<>();
        Matcher m = ISBN.matcher(text);
        while (m.find()) {
            String raw = m.group(1);
            String normalized = normalizeIsbn(raw);
            if (normalized == null) continue;
            if (isValidIsbn(normalized)) {
                isbnSet.add(normalized);
            }
        }

        return new DetailedIdentifiers(new ArrayList<>(doiSet), new ArrayList<>(isbnSet));
    }

    private static void collectDoi(String text, Pattern p, Set<String> out) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            String raw = m.group(1);
            String doi = normalizeDoi(raw);
            if (doi != null) out.add(doi);
        }
    }

    private static String normalizeDoi(String raw) {
        if (raw == null) return null;
        String s = raw.trim();

        // trim trailing punctuation common in PDFs
        s = s.replaceAll("[\\)\\]\\}\\.,;:]+$", "");

        // remove leading punctuation
        s = s.replaceAll("^[\\(\\[\\{]+", "");

        // DOI is case-insensitive; canonicalize to lower-case
        s = s.toLowerCase(Locale.ROOT);

        if (!s.startsWith("10.")) return null;
        // basic sanity: must contain slash
        if (!s.contains("/")) return null;

        return s;
    }

    private static String normalizeIsbn(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Keep digits and X only
        s = s.replaceAll("[^0-9Xx]", "");
        if (s.isEmpty()) return null;

        // upper X
        s = s.toUpperCase(Locale.ROOT);

        // accept 10 or 13 length only
        if (s.length() != 10 && s.length() != 13) return null;
        return s;
    }

    private static boolean isValidIsbn(String isbn) {
        if (isbn == null) return false;
        if (isbn.length() == 10) return isValidIsbn10(isbn);
        if (isbn.length() == 13) return isValidIsbn13(isbn);
        return false;
    }

    private static boolean isValidIsbn10(String isbn10) {
        // ISBN-10: 9 digits + check digit (0-9 or X=10)
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = isbn10.charAt(i);
            int v;
            if (i == 9 && c == 'X') v = 10;
            else if (c >= '0' && c <= '9') v = c - '0';
            else return false;

            sum += v * (10 - i);
        }
        return sum % 11 == 0;
    }

    private static boolean isValidIsbn13(String isbn13) {
        // ISBN-13: EAN-13 checksum (weights 1,3)
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            char c = isbn13.charAt(i);
            if (c < '0' || c > '9') return false;
            int v = c - '0';
            sum += (i % 2 == 0) ? v : (v * 3);
        }
        return sum % 10 == 0;
    }

    public record DetailedIdentifiers(List<String> dois, List<String> isbns) {}
}
