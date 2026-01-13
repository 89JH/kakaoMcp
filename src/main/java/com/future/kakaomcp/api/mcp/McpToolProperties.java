package com.future.kakaomcp.api.mcp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.mcp")
public class McpToolProperties {

    private final Sse sse = new Sse();
    private final Download download = new Download();
    private final FileLimit file = new FileLimit();
    private final Url url = new Url();
    private final Upload upload = new Upload();

    @Getter @Setter
    public static class Sse {
        private long timeoutMs = 30_000L;
    }

    @Getter @Setter
    public static class Download {
        private long maxBytes = 25L * 1024L * 1024L; // 25MB
        private int connectTimeoutMs = 10_000;
        private int readTimeoutMs = 60_000;
        private int maxRedirects = 8;
        private String userAgent = "Mozilla/5.0 (kakao-mcp)";
    }

    @Getter @Setter
    public static class FileLimit {
        private long maxBytes = 25L * 1024L * 1024L; // 25MB
    }

    @Getter @Setter
    public static class Url {
        private int maxUrlChars = 35_000;
        private int maxParas = 40;
        private int minParagraphChars = 40;
    }

    @Getter @Setter
    public static class Upload {
        // 업로드 임시파일 TTL
        private long ttlMs = 6L * 60L * 60L * 1000L; // 6h
    }
}
