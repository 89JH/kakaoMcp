package com.future.kakaomcp.application.doc;

import java.util.Collections;
import java.util.Set;

public record SummarizeMetadataContext(
        SourceType sourceType,
        String sourceUrl,
        String filename,
        Set<String> emails,
        Set<String> dois,
        Set<String> isbns,
        Set<String> authors,
        Set<String> affiliations
) {

    public enum SourceType { TEXT, URL, FILE }

    public static SummarizeMetadataContext empty() {
        return new SummarizeMetadataContext(
                SourceType.TEXT,
                null,
                null,
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet()
        );
    }

    public static SummarizeMetadataContext forText() {
        return empty();
    }

    public static SummarizeMetadataContext forUrl(String url) {
        return new SummarizeMetadataContext(
                SourceType.URL,
                url,
                null,
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet()
        );
    }

    public static SummarizeMetadataContext forFile(String filename) {
        return new SummarizeMetadataContext(
                SourceType.FILE,
                null,
                filename,
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet()
        );
    }

    // ----------------------------
    // PromptTemplateBuilder 호환용
    // ----------------------------
    public String primaryDoi() {
        return firstNonBlank(dois);
    }

    public String primaryIsbn() {
        return firstNonBlank(isbns);
    }

    /**
     * 현재 record에는 title 필드가 없으니 null 반환.
     * (필요하면 나중에 title을 필드로 추가해도 됨)
     */
    public String title() {
        return null;
    }

    /**
     * “문서급 입력” 판정:
     * - URL/FILE 이면 문서급으로 간주
     * - TEXT라도 DOI/ISBN 있으면 문서급으로 간주
     */
    public boolean isDocument() {
        if (sourceType == SourceType.URL || sourceType == SourceType.FILE) return true;
        return primaryDoi() != null || primaryIsbn() != null;
    }

    private static String firstNonBlank(Set<String> set) {
        if (set == null) return null;
        for (String s : set) {
            if (s != null) {
                String t = s.trim();
                if (!t.isBlank()) return t;
            }
        }
        return null;
    }
}
