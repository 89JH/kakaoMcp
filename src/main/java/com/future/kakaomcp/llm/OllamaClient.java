package com.future.kakaomcp.llm;

import com.future.kakaomcp.common.exception.BizException;
import com.future.kakaomcp.config.OllamaProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OllamaClient {

    private final WebClient.Builder webClientBuilder;
    private final OllamaProperties props;

    public String generate(String prompt, int maxChars) {
        String safePrompt = prompt == null ? "" : prompt;

        // 대략: 1 token ~= 3~4 chars (보수적으로 4로 잡음)
        int numPredict = Math.max(256, Math.min(2048, maxChars / 4));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", props.getTemperature());
        options.put("top_p", props.getTopP());
        options.put("num_ctx", props.getNumCtx());
        options.put("num_predict", numPredict);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("prompt", safePrompt);
        body.put("stream", false);
        body.put("options", options);

        try {
            OllamaGenerateResponse resp = webClientBuilder
                    .baseUrl(props.getBaseUrl())
                    .build()
                    .post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(OllamaGenerateResponse.class)
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .block();

            if (resp == null || resp.response() == null) return "";
            return resp.response().trim();

        } catch (WebClientResponseException e) {
            throw new BizException(
                    "OLLAMA_ERROR",
                    "Ollama 응답 오류: HTTP " + e.getStatusCode().value() + " - " + shrink(e.getResponseBodyAsString()),
                    e
            );
        } catch (WebClientRequestException e) {
            // 연결 자체 실패(서버 다운/거부/네트워크)
            throw new BizException(
                    "OLLAMA_DOWN",
                    "Ollama 연결 실패: " + e.getMessage() + " (baseUrl=" + props.getBaseUrl() + ")",
                    e
            );
        } catch (Exception e) {
            throw new BizException("OLLAMA_ERROR", "Ollama 호출 실패: " + e.getMessage(), e);
        }
    }

    private String shrink(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 240 ? t.substring(0, 240) + "..." : t;
    }

    /**
     * /api/generate 응답 중 우리가 쓰는 건 response 뿐
     */
    public record OllamaGenerateResponse(
            String model,
            String response,
            boolean done
    ) {}
}
