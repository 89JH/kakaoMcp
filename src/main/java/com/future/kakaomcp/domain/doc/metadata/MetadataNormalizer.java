package com.future.kakaomcp.domain.doc.metadata;

import java.util.*;
import java.util.regex.Pattern;

public class MetadataNormalizer {

    private static final List<String> TITLE_KEYS = List.of(
            "dc:title", "pdf:docinfo:title", "title", "meta:title"
    );

    private static final List<String> AUTHOR_KEYS = List.of(
            "dc:creator", "creator", "meta:author", "pdf:docinfo:creator", "Author"
    );

    private static final List<String> PUBLISHER_KEYS = List.of(
            "dc:publisher", "publisher"
    );

    private static final List<String> PUBLISHED_DATE_KEYS = List.of(
            "dc:date", "dcterms:issued", "prism:publicationDate"
    );

    private static final List<String> CREATED_KEYS = List.of(
            "dcterms:created", "pdf:docinfo:created", "Creation-Date", "created", "meta:creation-date"
    );

    private static final List<String> MODIFIED_KEYS = List.of(
            "dcterms:modified", "pdf:docinfo:modified", "Last-Modified", "modified"
    );

    private static final List<String> IDENTIFIER_KEYS = List.of(
            "prism:doi", "doi", "dc:identifier", "identifier", "xmp:Identifier"
    );

    private static final Pattern DOI_PATTERN =
            Pattern.compile("\\b10\\.\\d{4,9}/[-._;()/:A-Z0-9]+\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern ISBN_LIKE_PATTERN =
            Pattern.compile("\\b(?:97[89][- ]?)?\\d{1,5}[- ]?\\d{1,7}[- ]?\\d{1,7}[- ]?[0-9X]\\b",
                    Pattern.CASE_INSENSITIVE);

    public static Map<String, Object> normalize(Map<String, String> raw) {
        if (raw == null) raw = Map.of();

        Map<String, Object> out = new LinkedHashMap<>();

        String title = firstNonBlank(raw, TITLE_KEYS);
        String author = firstNonBlank(raw, AUTHOR_KEYS);
        String publisher = firstNonBlank(raw, PUBLISHER_KEYS);

        String createdAt = firstNonBlank(raw, CREATED_KEYS);
        String modifiedAt = firstNonBlank(raw, MODIFIED_KEYS);

        String contentType = pick(raw, "Content-Type", "content-type", "dc:format");
        String language = pick(raw, "dc:language", "language");

        // EPUB이면 발행일 후보를 채울 수 있음(정책에 따라 조정)
        String publishedDate = null;
        if (contentType != null && contentType.toLowerCase().contains("epub")) {
            publishedDate = firstNonBlank(raw, PUBLISHED_DATE_KEYS);
        }

        Set<String> identifiers = new LinkedHashSet<>();

        for (String k : IDENTIFIER_KEYS) {
            String v = raw.get(k);
            if (v == null || v.isBlank()) continue;
            identifiers.addAll(extractDoiAndIsbn(v));
        }

        for (String v : raw.values()) {
            if (v == null || v.isBlank()) continue;
            identifiers.addAll(extractDoiAndIsbn(v));
        }

        identifiers.removeIf(id -> !isValidDoiOrIsbn(id));

        out.put("title", title);
        out.put("author", author);
        out.put("publisher", publisher);

        out.put("createdAt", createdAt);
        out.put("modifiedAt", modifiedAt);
        out.put("publishedDate", publishedDate);

        out.put("identifiers", identifiers);
        out.put("contentType", contentType);
        out.put("language", language);

        return out;
    }

    private static String pick(Map<String, String> raw, String... keys) {
        for (String k : keys) {
            String v = raw.get(k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String firstNonBlank(Map<String, String> raw, List<String> keys) {
        for (String k : keys) {
            String v = raw.get(k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static Set<String> extractDoiAndIsbn(String text) {
        Set<String> out = new LinkedHashSet<>();
        String s = String.valueOf(text);

        // ✅ DOI/ISBN 흔한 접두/URL 형태 제거(AAI.pdf 같은 본문/메타에 섞여있는 케이스 대응)
        String normalized = s
                .replace("https://doi.org/", "")
                .replace("http://doi.org/", "")
                .replace("https://dx.doi.org/", "")
                .replace("http://dx.doi.org/", "")
                .replace("urn:isbn:", "")
                .replace("URN:ISBN:", "")
                .replace("isbn:", "")
                .replace("ISBN:", "")
                .replace("doi:", "")
                .replace("DOI:", "");

        var doi = DOI_PATTERN.matcher(normalized);
        while (doi.find()) out.add(doi.group());

        var isbn = ISBN_LIKE_PATTERN.matcher(normalized);
        while (isbn.find()) out.add(isbn.group());

        return out;
    }

    private static boolean isValidDoiOrIsbn(String id) {
        if (id == null) return false;
        String t = id.trim();
        if (t.isEmpty()) return false;

        if (t.toLowerCase().startsWith("10.")) return true;

        String digits = t.replaceAll("[^0-9Xx]", "");
        return digits.length() == 10 || digits.length() == 13;
    }
}
