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
public class GoogleBooksClient {

    private final EnrichProperties props;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl(props.getGoogleBooks().getBaseUrl())
                .build();
    }

    /**
     * Google Books Volumes list endpoint:
     * GET /volumes?q=isbn:{isbn} :contentReference[oaicite:4]{index=4}
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> fetchByIsbn(String isbn) {
        try {
            String q = "isbn:" + isbn;
            String apiKey = props.getGoogleBooks().getApiKey();

            String uri = "/volumes?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
            if (apiKey != null && !apiKey.isBlank()) {
                uri += "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            }

            Map<String, Object> res = client().get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        log.warn("GoogleBooks error: status={}, isbn={}", resp.getStatusCode(), isbn);
                    })
                    .body(Map.class);

            if (res == null) return Optional.empty();

            Object items = res.get("items");
            if (!(items instanceof List<?> list) || list.isEmpty()) return Optional.empty();

            Object first = list.get(0);
            if (!(first instanceof Map<?, ?>)) return Optional.empty();

            return Optional.of((Map<String, Object>) first);

        } catch (Exception e) {
            log.warn("GoogleBooks fetch failed: isbn={}, err={}", isbn, e.toString());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toEnriched(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (item == null) return out;

        out.put("source", "googleBooks");

        Object volumeInfoObj = item.get("volumeInfo");
        if (!(volumeInfoObj instanceof Map<?, ?> volumeInfo)) return out;

        Map<String, Object> vi = (Map<String, Object>) volumeInfo;

        out.put("title", asString(vi.get("title")));
        out.put("subtitle", asString(vi.get("subtitle")));
        out.put("publisher", asString(vi.get("publisher")));
        out.put("publishedDate", asString(vi.get("publishedDate")));
        out.put("language", asString(vi.get("language")));

        Object authors = vi.get("authors");
        if (authors instanceof List<?> list) {
            List<String> a = new ArrayList<>();
            for (Object x : list) if (x != null) a.add(String.valueOf(x));
            out.put("authors", a);
        }

        // industryIdentifiers에 ISBN_10/ISBN_13 존재할 수 있음
        Object ids = vi.get("industryIdentifiers");
        if (ids instanceof List<?> list) {
            List<Map<String, Object>> normIds = new ArrayList<>();
            for (Object x : list) {
                if (x instanceof Map<?, ?> m) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    Object type = ((Map<String, Object>) m).get("type");
                    Object identifier = ((Map<String, Object>) m).get("identifier");
                    if (type != null) t.put("type", String.valueOf(type));
                    if (identifier != null) t.put("identifier", String.valueOf(identifier));
                    if (!t.isEmpty()) normIds.add(t);
                }
            }
            out.put("industryIdentifiers", normIds);
        }

        return out;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
