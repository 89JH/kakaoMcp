package com.future.kakaomcp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.summarize.ollama")
public class OllamaProperties {

    /**
     * 예) http://localhost:11434
     */
    private String baseUrl = "http://localhost:11434";

    /**
     * 예) qwen2.5:7b
     */
    private String model = "qwen2.5:7b";

    private double temperature = 0.2;
    private double topP = 0.9;

    /**
     * 컨텍스트 길이 (모델/환경에 따라 조정)
     */
    private int numCtx = 8192;

    /**
     * 요청 타임아웃 (ms)
     */
    private int timeoutMs = 120_000;
}
