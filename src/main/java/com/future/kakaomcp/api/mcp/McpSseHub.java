package com.future.kakaomcp.api.mcp;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpSseHub {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe() {
        // 0L = timeout 없음(nginx/프록시가 있으면 별도 타임아웃 고려)
        SseEmitter emitter = new SseEmitter(0L);
        String id = UUID.randomUUID().toString();

        emitters.put(id, emitter);

        emitter.onCompletion(() -> emitters.remove(id));
        emitter.onTimeout(() -> emitters.remove(id));
        emitter.onError((e) -> emitters.remove(id));

        // 연결 직후 한 번 보내기(프록시 버퍼링/연결 확인용)
        try {
            emitter.send(SseEmitter.event()
                    .name("hello")
                    .data("connected:" + id)
                    .id(String.valueOf(Instant.now().toEpochMilli())));
        } catch (IOException ignored) {
            emitters.remove(id);
        }

        return emitter;
    }

    public int broadcast(String eventName, Object data) {
        int sent = 0;
        for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event()
                        .name(eventName)
                        .data(data)
                        .id(String.valueOf(Instant.now().toEpochMilli())));
                sent++;
            } catch (Exception e) {
                emitters.remove(entry.getKey());
            }
        }
        return sent;
    }
}
