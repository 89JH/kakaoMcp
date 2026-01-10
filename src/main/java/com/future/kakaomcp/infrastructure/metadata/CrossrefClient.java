package com.future.kakaomcp.infrastructure.metadata;

import com.future.kakaomcp.config.EnrichProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrossrefClient {

    private final EnrichProperties props;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl(props.getCrossref().getBaseUrl())
                .build();
    }

    /**
     * Crossref works endpoint: /works/{doi}
     * polite pool을 위해 mailto 파라미터 사용 권장 :contentReference[oaicite:3]{index=3}
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> fetchByDoi(String doi) {
        try {
            String encDoi = URLEncoder.encode(doi, StandardCharsets.UTF_8);
            String mailto = props.getCrossref().getMailto();
            String uri = "/works/" + encDoi + (mailto != null && !mailto.isBlank()
                    ? "?mailto=" + URLEncoder.encode(mailto, StandardCharsets.UTF_8)
                    : "");

            Map<String, Object> res = client().get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        log.warn("Crossref error: status={}, doi={}", resp.getStatusCode(), doi);
                    })
                    .body(Map.class);

            if (res == null) return Optional.empty();

            Object message = res.get("message");
            if (!(message instanceof Map)) return Optional.empty();

            return Optional.of((Map<String, Object>) message);
        } catch (Exception e) {
            log.warn("Crossref fetch failed: doi={}, err={}", doi, e.toString());
            return Optional.empty();
        }
    }

    public static Map<String, Object> toEnriched(Map<String, Object> message) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (message == null) return out;

        out.put("source", "crossref");
        out.put("doi", asString(message.get("DOI")));
        out.put("type", asString(message.get("type")));
        out.put("title", firstFromList(message.get("title")));
        out.put("publisher", asString(message.get("publisher")));
        out.put("containerTitle", firstFromList(message.get("container-title")));

        // author는 [{given,family,name}] 등 다양해서 최소 형태로 정리
        out.put("authors", normalizeAuthors(message.get("author")));

        // published date는 구조가 다양해서 "best effort"
        out.put("published", pickDate(message,
                "published-print",
                "published-online",
                "created",
                "issued"));

        return out;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstFromList(Object o) {
        if (o instanceof List<?> list && !list.isEmpty()) {
            Object v = list.get(0);
            return v == null ? null : String.valueOf(v);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalizeAuthors(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object it : list) {
            if (!(it instanceof Map<?, ?> m)) continue;
            Map<String, Object> a = new LinkedHashMap<>();
            Object given = ((Map<String, Object>) m).get("given");
            Object family = ((Map<String, Object>) m).get("family");
            Object name = ((Map<String, Object>) m).get("name");

            if (name != null) a.put("name", String.valueOf(name));
            if (given != null) a.put("given", String.valueOf(given));
            if (family != null) a.put("family", String.valueOf(family));

            if (!a.isEmpty()) out.add(a);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String pickDate(Map<String, Object> message, String... keys) {
        for (String k : keys) {
            Object v = message.get(k);
            String date = tryParseDate(v);
            if (date != null) return date;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String tryParseDate(Object v) {
        // Crossref date-parts 형식: { "date-parts": [[YYYY,MM,DD]] }
        if (v instanceof Map<?, ?> m) {
            Object dp = ((Map<String, Object>) m).get("date-parts");
            if (dp instanceof List<?> outer && !outer.isEmpty()) {
                Object inner0 = outer.get(0);
                if (inner0 instanceof List<?> inner && !inner.isEmpty()) {
                    String y = inner.size() > 0 ? String.valueOf(inner.get(0)) : null;
                    String mo = inner.size() > 1 ? pad2(inner.get(1)) : null;
                    String d = inner.size() > 2 ? pad2(inner.get(2)) : null;

                    if (y == null) return null;
                    if (mo == null) return y;
                    if (d == null) return y + "-" + mo;
                    return y + "-" + mo + "-" + d;
                }
            }
        }
        return null;
    }

    private static String pad2(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.length() == 1) return "0" + s;
        return s;
    }
}
