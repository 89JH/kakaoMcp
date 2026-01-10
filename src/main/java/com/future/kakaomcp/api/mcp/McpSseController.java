package com.future.kakaomcp.api.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/mcp-sse")
@RequiredArgsConstructor
public class McpSseController {

    private final McpSseHub hub;

    // ✅ 클라이언트 구독 (SSE)
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse() {
        return hub.subscribe();
    }

    // ✅ 서버가 이벤트 푸시(테스트/최소)
    @PostMapping("/emit")
    public Map<String, Object> emit(@RequestBody Map<String, Object> body) {
        String event = String.valueOf(body.getOrDefault("event", "message"));
        Object data = body.getOrDefault("data", body);
        int sent = hub.broadcast(event, data);
        return Map.of("ok", true, "sent", sent);
    }
}
