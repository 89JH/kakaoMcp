package com.future.kakaomcp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.enrich")
public class EnrichProperties {
    private boolean enabled = true;

    private Crossref crossref = new Crossref();
    private GoogleBooks googleBooks = new GoogleBooks();

    @Getter @Setter
    public static class Crossref {
        private String baseUrl = "https://api.crossref.org";
        private String mailto = "";
        private int timeoutMs = 4000;
    }

    @Getter @Setter
    public static class GoogleBooks {
        private String baseUrl = "https://www.googleapis.com/books/v1";
        private String apiKey = "";
        private int timeoutMs = 4000;
    }
}
