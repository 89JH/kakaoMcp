package com.future.kakaomcp.api.mcp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app")
public class McpToolProperties {

    /** MCP initialize 응답용 (McpHttpController에서 사용) */
    private String serverName = "kakao-mcp";
    private String serverVersion = "1.0.0";

    private final Sse sse = new Sse();
    private final Download download = new Download();
    private final FileLimit file = new FileLimit();
    private final Url url = new Url();
    private final Upload upload = new Upload();

    // ✅ 요약 프롬프트/자동 bullet 설정 (PromptTemplateBuilder에서 사용)
    private final Prompt prompt = new Prompt();
    private final Summarize summarize = new Summarize();

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

    @Getter @Setter
    public static class Prompt {
        private String commonRules = "";
        private final Template template = new Template();

        @Getter @Setter
        public static class Template {
            /** application.yml: app.prompt.template.abstract-template */
            private String abstractTemplate = "";
            /** application.yml: app.prompt.template.final-template */
            private String finalTemplate = "";
        }
    }

    @Getter @Setter
    public static class Summarize {
        private final AutoBullets autoBullets = new AutoBullets();

        @Getter @Setter
        public static class AutoBullets {
            private int tier1MaxChars = 1200;
            private int tier2MaxChars = 2500;
            private int tier3MaxChars = 4500;
            private int tier4MaxChars = 8000;
            private int tier5MaxChars = 14000;

            private int tier1 = 5;
            private int tier2 = 6;
            private int tier3 = 8;
            private int tier4 = 12;
            private int tier5 = 18;

            /** final 모드에서 추가할 bullet 수(최대 20 내) */
            private int finalExtra = 2;

            /** 청크 요약일 때 bullet 상한 */
            private int chunkCap = 10;
        }
    }
}
