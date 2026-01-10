package com.future.kakaomcp.application.doc;

import com.future.kakaomcp.config.EnrichProperties;
import com.future.kakaomcp.infrastructure.metadata.CrossrefClient;
import com.future.kakaomcp.infrastructure.metadata.GoogleBooksClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class MetadataEnricherService {

    private final EnrichProperties props;
    private final CrossrefClient crossrefClient;
    private final GoogleBooksClient googleBooksClient;

    public Map<String, Object> enrich(Set<String> identifiers) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!props.isEnabled() || identifiers == null || identifiers.isEmpty()) {
            return out;
        }

        String doi = pickDoi(identifiers);
        String isbn = pickIsbn(identifiers);

        if (doi != null) {
            crossrefClient.fetchByDoi(doi)
                    .map(CrossrefClient::toEnriched)
                    .ifPresent(m -> out.put("crossref", m));
        }

        if (isbn != null) {
            googleBooksClient.fetchByIsbn(isbn)
                    .map(GoogleBooksClient::toEnriched)
                    .ifPresent(m -> out.put("googleBooks", m));
        }

        // 대표값(merging): googleBooks의 book metadata 우선, 없으면 crossref
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("doi", doi);
        merged.put("isbn", isbn);

        Map<String, Object> gb = asMap(out.get("googleBooks"));
        Map<String, Object> cr = asMap(out.get("crossref"));

        // title/publisher/publishedDate/authors는 book엔 googleBooks가 더 적합
        merged.put("title", firstNonNull(gb.get("title"), cr.get("title")));
        merged.put("publisher", firstNonNull(gb.get("publisher"), cr.get("publisher")));
        merged.put("publishedDate", firstNonNull(gb.get("publishedDate"), cr.get("published")));
        merged.put("authors", firstNonNull(gb.get("authors"), cr.get("authors")));

        out.put("merged", merged);
        return out;
    }

    private String pickDoi(Set<String> ids) {
        for (String s : ids) {
            if (s == null) continue;
            String t = s.trim();
            if (t.toLowerCase().startsWith("10.")) return t;
        }
        return null;
    }

    private String pickIsbn(Set<String> ids) {
        for (String s : ids) {
            if (s == null) continue;
            String digits = s.replaceAll("[^0-9Xx]", "");
            if (digits.length() == 13 || digits.length() == 10) return digits;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    private Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }
}
