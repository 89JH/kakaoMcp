package com.future.kakaomcp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.summarize.url")
public class SummarizeUrlProperties {

    /**
     * HTML 본문 추출 후 최대 글자 수 제한
     */
    private int maxChars = 50_000;

    /**
     * URL 요약에서 허용하는 대략적 최대 청크 수 (길이 제한으로 간접 제어)
     */
    private int maxChunks = 8;

    /**
     * "청크 1개당 대략 글자 수" (너 chunker 세팅에 맞춰 튜닝)
     */
    private int approxChunkChars = 8_000;

    public int effectiveMaxChars() {
        int byChunks = Math.max(1, maxChunks) * Math.max(1000, approxChunkChars);
        return Math.min(Math.max(1000, maxChars), byChunks);
    }
}
