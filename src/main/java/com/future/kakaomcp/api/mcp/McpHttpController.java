package com.future.kakaomcp.api.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.future.kakaomcp.api.doc.dto.SummarizeResponse;
import com.future.kakaomcp.application.doc.DocFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.future.kakaomcp.api.mcp.JsonRpcDto.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mcp")
public class McpHttpController {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String JSONRPC = "2.0";

    private static final String PROTOCOL_LATEST = "2025-03-26";
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("2025-03-26");

    private static final String TOOL_SUMMARIZE_TEXT = "summarize_text";
    private static final String TOOL_SUMMARIZE_URL = "summarize_url";
    private static final String TOOL_SUMMARIZE_FILE = "summarize_file";
    private static final String TOOL_SUMMARIZE_DOCUMENT = "summarize_document"; // legacy alias

    private final DocFacade docFacade;
    private final McpToolProperties toolProperties;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setName("mcp-sse-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    @PostMapping(
            consumes = { MediaType.APPLICATION_JSON_VALUE, "application/mcp+json" },
            produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE }
    )
    public Object handle(@RequestHeader Map<String, String> headers,
                         @RequestBody String rawBody) {

        boolean wantSse = headerContains(headers, "accept", "text/event-stream");

        Map<String, Object> req;
        try {
            req = OM.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception e) {
            Map<String, Object> out = error(null, -32700, "Parse error", Map.of("message", e.getMessage()));
            if (wantSse) return sseSingle(out);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out);
        }

        String method = asString(req.get("method"));
        Object id = req.get("id");
        Map<String, Object> params = asMap(req.get("params"));

        // ✅ A안: 카카오로 보이는 요청은 tools/call만 JSON 단건으로 강제
        boolean kakaoLike = isKakaoLike(headers);
        if (wantSse && "tools/call".equals(method) && kakaoLike) {
            log.info("[MCP] Kakao-like client detected -> force JSON for tools/call. ua={}",
                    safeHeader(headers, "user-agent"));
            Map<String, Object> out = dispatch(method, id, params);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(out);
        }

        // ✅ SSE 요청이면: tools/call은 스트리밍, 나머지는 단건 SSE
        if (wantSse) {
            if ("tools/call".equals(method)) {
                return sseToolsCall(id, params);
            }
            Map<String, Object> out = dispatch(method, id, params);
            return sseSingle(out);
        }

        // ✅ JSON 단건
        Map<String, Object> out = dispatch(method, id, params);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(out);
    }

    private Map<String, Object> dispatch(String method, Object id, Map<String, Object> params) {
        try {
            if ("initialize".equals(method)) return handleInitialize(id, params);
            if ("notifications/initialized".equals(method)) return okResult(id, Map.of("ok", true));
            if ("tools/list".equals(method)) return handleToolsList(id);
            if ("tools/call".equals(method)) return handleToolsCall(id, params);
            return error(id, -32601, "Method not found", Map.of("method", method));
        } catch (Exception e) {
            log.error("MCP error", e);
            return error(id, -32603, "Internal error", Map.of("message", e.getMessage()));
        }
    }

    // -------------------------
    // SSE: 단건 응답 (tools/list 등)
    // -------------------------
    private ResponseEntity<SseEmitter> sseSingle(Map<String, Object> payload) {
        SseEmitter emitter = new SseEmitter(0L);

        HttpHeaders h = new HttpHeaders();
        h.add("X-Accel-Buffering", "no");
        h.add("Cache-Control", "no-cache");
        h.add("Connection", "keep-alive");

        sseExecutor.execute(() -> {
            try {
                sendMessage(emitter, payload);
                emitter.complete();
            } catch (Exception e) {
                safeCompleteWithError(emitter, e);
            }
        });

        return ResponseEntity.ok()
                .headers(h)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    // -------------------------
    // SSE: tools/call 스트리밍
    // -------------------------
    private ResponseEntity<SseEmitter> sseToolsCall(Object id, Map<String, Object> params) {
        SseEmitter emitter = new SseEmitter(0L);

        HttpHeaders h = new HttpHeaders();
        h.add("X-Accel-Buffering", "no");
        h.add("Cache-Control", "no-cache");
        h.add("Connection", "keep-alive");

        final long startedAt = System.currentTimeMillis();
        final AtomicBoolean done = new AtomicBoolean(false);

        // heartbeat도 data(progress)로 보냄
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("mcp-sse-heartbeat-" + t.getId());
            t.setDaemon(true);
            return t;
        });

        ScheduledFuture<?> hb = scheduler.scheduleAtFixedRate(() -> {
            if (done.get()) return;
            try {
                sendProgress(emitter, id, "heartbeat",
                        "still working",
                        Map.of("elapsedMs", (System.currentTimeMillis() - startedAt)));
            } catch (Exception ignore) {}
        }, 15, 15, TimeUnit.SECONDS);

        sseExecutor.execute(() -> {
            try {
                if (params == null) {
                    sendMessage(emitter, error(id, -32602, "Invalid params", Map.of("message", "params is required")));
                    done.set(true);
                    emitter.complete();
                    return;
                }

                String name = asString(params.get("name"));
                Map<String, Object> args = asMap(params.get("arguments"));

                if (name == null || name.isBlank()) {
                    sendMessage(emitter, error(id, -32602, "Invalid params", Map.of("message", "params.name is required")));
                    done.set(true);
                    emitter.complete();
                    return;
                }

                sendProgress(emitter, id, "start",
                        "tools/call started",
                        Map.of("tool", name, "ts", System.currentTimeMillis()));

                Map<String, Object> finalOut;

                switch (name) {

                    // ✅ summarize_text: args 직접 파싱
                    case TOOL_SUMMARIZE_TEXT:
                    case TOOL_SUMMARIZE_DOCUMENT: {
                        String text = asString(args.get("text"));
                        String mode = asString(args.get("mode"));
                        Boolean includeMeta = asBoolean(args.get("includeMeta"), false);

                        if (text == null || text.isBlank()) {
                            finalOut = error(id, -32602, "Invalid params", Map.of("message", "text is required"));
                            break;
                        }

                        sendProgress(emitter, id, "processing",
                                "summarize_text running",
                                Map.of("mode", mode == null ? "" : mode));

                        Object result = docFacade.summarizeText(text, mode, includeMeta);
                        String out = extractText(result);
                        finalOut = okContentText(id, out);
                        break;
                    }

                    case TOOL_SUMMARIZE_URL: {
                        String url = asString(args.get("url"));
                        String mode = asString(args.get("mode"));
                        Boolean includeMeta = asBoolean(args.get("includeMeta"), false);

                        if (url == null || url.isBlank()) {
                            finalOut = error(id, -32602, "Invalid params", Map.of("message", "url is required"));
                            break;
                        }

                        if (looksLikeFileUrl(url)) {
                            sendProgress(emitter, id, "download",
                                    "downloading file url",
                                    Map.of("url", url));

                            URLDownload d = download(url);

                            sendProgress(emitter, id, "extract",
                                    "file downloaded; extracting & summarizing",
                                    Map.of("filename", d.filename, "bytes", d.bytes.length));

                            Object result = docFacade.summarizeBytes(d.bytes, d.filename, mode, includeMeta);
                            String out = extractText(result);
                            finalOut = okContentText(id, out);
                            break;
                        }

                        sendProgress(emitter, id, "extract",
                                "fetching url html/text",
                                Map.of("url", url));

                        Object result = docFacade.summarizeUrl(url, mode, includeMeta);
                        String out = extractText(result);
                        finalOut = okContentText(id, out);
                        break;
                    }

                    case TOOL_SUMMARIZE_FILE: {
                        String filename = asString(args.get("filename"));
                        String base64 = asString(args.get("contentBase64"));
                        String mode = asString(args.get("mode"));
                        Boolean includeMeta = asBoolean(args.get("includeMeta"), false);

                        if (base64 == null || base64.isBlank()) {
                            finalOut = error(id, -32602, "Invalid params", Map.of("message", "contentBase64 is required"));
                            break;
                        }

                        if (filename == null || filename.isBlank()) filename = "upload.bin";

                        sendProgress(emitter, id, "decode",
                                "decoding base64",
                                Map.of("filename", filename));

                        byte[] bytes = decodeBase64DataUrl(base64);

                        sendProgress(emitter, id, "extract",
                                "file decoded; extracting & summarizing",
                                Map.of("filename", filename, "bytes", bytes.length));

                        Object result = docFacade.summarizeBytes(bytes, filename, mode, includeMeta);
                        String out = extractText(result);
                        finalOut = okContentText(id, out);
                        break;
                    }

                    default:
                        finalOut = error(id, -32601, "Tool not found", Map.of("name", name));
                }

                long tookMs = System.currentTimeMillis() - startedAt;
                sendProgress(emitter, id, "done", "completed", Map.of("tookMs", tookMs));

                sendMessage(emitter, finalOut);

                done.set(true);
                emitter.complete();

            } catch (Exception e) {
                done.set(true);
                safeCompleteWithError(emitter, e);
            } finally {
                hb.cancel(true);
                scheduler.shutdownNow();
            }
        });

        return ResponseEntity.ok()
                .headers(h)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    // -------------------------
    // initialize
    // -------------------------
    private Map<String, Object> handleInitialize(Object id, Map<String, Object> params) {
        String requested = null;
        if (params != null) requested = asString(params.get("protocolVersion"));

        String chosen;
        if (requested == null || requested.isBlank()) chosen = PROTOCOL_LATEST;
        else if (SUPPORTED_PROTOCOLS.contains(requested)) chosen = requested;
        else chosen = PROTOCOL_LATEST;

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", toolProperties.getServerName());
        serverInfo.put("version", toolProperties.getServerVersion());

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", chosen);
        result.put("serverInfo", serverInfo);
        result.put("capabilities", capabilities);

        return okResult(id, result);
    }

    // -------------------------
    // tools/list
    // -------------------------
    private Map<String, Object> handleToolsList(Object id) {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_TEXT,
                "description", "텍스트를 요약합니다. (SSE 진행 이벤트 지원)",
                "inputSchema", Map.of(
                        "type", "object",
                        "required", List.of("text"),
                        "properties", Map.of(
                                "text", Map.of("type", "string", "description", "요약할 텍스트"),
                                "mode", Map.of("type", "string", "description", "abstract|final", "default", "final"),
                                "includeMeta", Map.of("type", "boolean", "default", false)
                        )
                )
        ));

        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_URL,
                "description", "URL을 요약합니다. (SSE 진행 이벤트 지원)",
                "inputSchema", Map.of(
                        "type", "object",
                        "required", List.of("url"),
                        "properties", Map.of(
                                "url", Map.of("type", "string", "description", "요약할 URL"),
                                "mode", Map.of("type", "string", "description", "abstract|final", "default", "abstract"),
                                "includeMeta", Map.of("type", "boolean", "default", false)
                        )
                )
        ));

        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_FILE,
                "description", "Base64 파일(PDF/EPUB/TXT 등)을 요약합니다. (SSE 진행 이벤트 지원)",
                "inputSchema", Map.of(
                        "type", "object",
                        "required", List.of("contentBase64"),
                        "properties", Map.of(
                                "contentBase64", Map.of("type", "string", "description", "파일 base64 (data:...;base64, 가능)"),
                                "filename", Map.of("type", "string", "default", "upload.bin"),
                                "mode", Map.of("type", "string", "description", "abstract|final", "default", "final"),
                                "includeMeta", Map.of("type", "boolean", "default", false)
                        )
                )
        ));

        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_DOCUMENT,
                "description", "summarize_text와 동일 (호환용)",
                "inputSchema", Map.of(
                        "type", "object",
                        "required", List.of("text"),
                        "properties", Map.of(
                                "text", Map.of("type", "string"),
                                "mode", Map.of("type", "string", "description", "abstract|final", "default", "final"),
                                "includeMeta", Map.of("type", "boolean", "default", false)
                        )
                )
        ));

        return okResult(id, Map.of("tools", tools));
    }

    // -------------------------
    // tools/call JSON 단건
    // -------------------------
    private Map<String, Object> handleToolsCall(Object id, Map<String, Object> params) {
        if (params == null) return error(id, -32602, "Invalid params", Map.of("message", "params is required"));

        String name = asString(params.get("name"));
        Map<String, Object> args = asMap(params.get("arguments"));

        if (name == null) return error(id, -32602, "Invalid params", Map.of("message", "params.name is required"));

        switch (name) {
            case TOOL_SUMMARIZE_TEXT:
            case TOOL_SUMMARIZE_DOCUMENT:
                return callSummarizeText(id, args);

            case TOOL_SUMMARIZE_URL:
                try {
                    return callSummarizeUrl(id, args);
                } catch (Exception e) {
                    log.error("summarize_url failed", e);
                    return error(id, -32603, "Internal error", Map.of("message", e.getMessage()));
                }

            case TOOL_SUMMARIZE_FILE:
                return callSummarizeFile(id, args);

            default:
                return error(id, -32601, "Tool not found", Map.of("name", name));
        }
    }

    private Map<String, Object> callSummarizeText(Object id, Map<String, Object> args) {
        String text = asString(args.get("text"));
        String mode = asString(args.get("mode"));
        Boolean includeMeta = asBoolean(args.get("includeMeta"), false);

        if (text == null || text.isBlank()) {
            return error(id, -32602, "Invalid params", Map.of("message", "text is required"));
        }

        Object result = docFacade.summarizeText(text, mode, includeMeta);
        String out = extractText(result);
        return okContentText(id, out);
    }

    private Map<String, Object> callSummarizeUrl(Object id, Map<String, Object> args) throws Exception {
        String url = asString(args.get("url"));
        String mode = asString(args.get("mode"));
        Boolean includeMeta = asBoolean(args.get("includeMeta"), false);

        if (url == null || url.isBlank()) {
            return error(id, -32602, "Invalid params", Map.of("message", "url is required"));
        }

        if (looksLikeFileUrl(url)) {
            URLDownload d = download(url);
            Object result = docFacade.summarizeBytes(d.bytes, d.filename, mode, includeMeta);
            String out = extractText(result);
            return okContentText(id, out);
        }

        Object result = docFacade.summarizeUrl(url, mode, includeMeta);
        String out = extractText(result);
        return okContentText(id, out);
    }

    private Map<String, Object> callSummarizeFile(Object id, Map<String, Object> args) {
        String filename = asString(args.get("filename"));
        String base64 = asString(args.get("contentBase64"));
        String mode = asString(args.get("mode"));
        Boolean includeMeta = asBoolean(args.get("includeMeta"), false);

        if (base64 == null || base64.isBlank()) {
            return error(id, -32602, "Invalid params", Map.of("message", "contentBase64 is required"));
        }

        byte[] bytes = decodeBase64DataUrl(base64);
        if (filename == null || filename.isBlank()) filename = "upload.bin";

        Object result = docFacade.summarizeBytes(bytes, filename, mode, includeMeta);
        String out = extractText(result);
        return okContentText(id, out);
    }

    // -------------------------
    // JSON-RPC helpers
    // -------------------------
    private Map<String, Object> okResult(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", JSONRPC);
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private Map<String, Object> okContentText(Object id, String text) {
        Map<String, Object> result = Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", text == null ? "" : text
                ))
        );
        return okResult(id, result);
    }

    private Map<String, Object> error(Object id, int code, String message, Object data) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        if (data != null) err.put("data", data);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", JSONRPC);
        m.put("id", id);
        m.put("error", err);
        return m;
    }

    // -------------------------
    // SSE helpers
    // -------------------------
    private void sendMessage(SseEmitter emitter, Map<String, Object> payload) throws IOException {
        emitter.send(SseEmitter.event()
                .name("message")
                .id("m-" + Instant.now().toEpochMilli())
                .data(toJson(payload)));
    }

    private void sendProgress(SseEmitter emitter, Object id, String step, String message, Map<String, Object> data) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", id);
        params.put("step", step);
        params.put("message", message);
        params.put("ts", System.currentTimeMillis());
        if (data != null && !data.isEmpty()) params.put("data", data);

        Map<String, Object> notif = new LinkedHashMap<>();
        notif.put("jsonrpc", JSONRPC);
        notif.put("method", "notifications/progress");
        notif.put("params", params);

        emitter.send(SseEmitter.event()
                .name("message")
                .id("p-" + Instant.now().toEpochMilli())
                .data(toJson(notif)));
    }

    private String toJson(Object payload) {
        try {
            return OM.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
        }
    }

    private void safeCompleteWithError(SseEmitter emitter, Exception e) {
        try {
            sendMessage(emitter, error(null, -32603, "Internal error", Map.of("message", e.getMessage())));
        } catch (Exception ignore) {
        } finally {
            try { emitter.completeWithError(e); } catch (Exception ignore2) {}
        }
    }

    // -------------------------
    // client detection (A안 핵심)
    // -------------------------
    private boolean isKakaoLike(Map<String, String> headers) {
        // 널리 잡되, 오탐으로 curl이 걸리면 안 되므로 "kakao" 계열 문자열을 중심으로 판단
        String ua = safeHeader(headers, "user-agent").toLowerCase(Locale.ROOT);
        String origin = safeHeader(headers, "origin").toLowerCase(Locale.ROOT);
        String referer = safeHeader(headers, "referer").toLowerCase(Locale.ROOT);
        String client = safeHeader(headers, "x-client-name").toLowerCase(Locale.ROOT);
        String via = safeHeader(headers, "via").toLowerCase(Locale.ROOT);

        // 카카오/카카오워크/카카오브레인/카카오mcp 등에서 흔히 보일 수 있는 키워드들
        return ua.contains("kakao")
                || origin.contains("kakao")
                || referer.contains("kakao")
                || client.contains("kakao")
                || via.contains("kakao");
    }

    private static boolean headerContains(Map<String, String> headers, String key, String token) {
        for (var e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                String v = e.getValue();
                if (v != null && v.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    private static String safeHeader(Map<String, String> headers, String key) {
        for (var e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue() == null ? "" : e.getValue();
            }
        }
        return "";
    }

    // -------------------------
    // misc helpers
    // -------------------------
    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static Boolean asBoolean(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s)) return true;
        if ("false".equals(s)) return false;
        return def;
    }

    private static boolean looksLikeFileUrl(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.endsWith(".pdf") || u.contains(".pdf?")
                || u.endsWith(".epub") || u.contains(".epub?")
                || u.endsWith(".txt") || u.contains(".txt?")
                || u.endsWith(".docx") || u.contains(".docx?");
    }

    private static byte[] decodeBase64DataUrl(String contentBase64) {
        String s = contentBase64.trim();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) s = s.substring(comma + 1);
        return Base64.getDecoder().decode(s);
    }

    private static String extractText(Object result) {
        if (result == null) return "";
        if (result instanceof String s) return s;

        if (result instanceof SummarizeResponse sr) {
            return sr.getSummaryText() == null ? "" : sr.getSummaryText();
        }

        if (result instanceof Map<?, ?> m) {
            Object summaryText = m.get("summaryText");
            if (summaryText instanceof String s) return s;

            Object content = m.get("content");
            if (content instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> cm) {
                    Object text = cm.get("text");
                    if (text instanceof String s2) return s2;
                }
            }
        }

        return String.valueOf(result);
    }

    // -------------------------
    // URL download helpers
    // -------------------------
    private record URLDownload(byte[] bytes, String filename) {}

    private URLDownload download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);

        conn.setConnectTimeout(toolProperties.getDownload().getConnectTimeoutMs());
        conn.setReadTimeout(toolProperties.getDownload().getReadTimeoutMs());

        conn.setRequestProperty("User-Agent", toolProperties.getDownload().getUserAgent());
        conn.setRequestProperty("Accept-Encoding", "identity");

        int code = conn.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);

        String disp = Optional.ofNullable(conn.getHeaderField("Content-Disposition")).orElse("");
        String filename = guessFilename(url, disp);

        byte[] bytes;
        try (InputStream in = conn.getInputStream()) {
            bytes = readAllLimited(in, toolProperties.getDownload().getMaxBytes());
        }

        log.debug("download ok: url={}, bytes={}", url, bytes.length);
        return new URLDownload(bytes, filename);
    }

    private static byte[] readAllLimited(InputStream in, long maxBytes) throws IOException {
        int init = (int) Math.min(64L * 1024L, Math.max(1L, maxBytes));
        ByteArrayOutputStream bos = new ByteArrayOutputStream(init);
        byte[] buf = new byte[8192];
        long total = 0;
        int r;
        while ((r = in.read(buf)) != -1) {
            total += r;
            if (total > maxBytes) throw new IOException("Downloaded file too large: " + total + " > " + maxBytes);
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }

    private static String guessFilename(String url, String contentDisposition) {
        String lower = contentDisposition.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("filename=");
        if (idx >= 0) {
            String v = contentDisposition.substring(idx + "filename=".length()).trim().replace("\"", "");
            if (!v.isBlank()) return v;
        }

        int slash = url.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < url.length()) {
            String name = url.substring(slash + 1);
            int q = name.indexOf('?');
            if (q > 0) name = name.substring(0, q);
            if (!name.isBlank()) return name;
        }

        return "download.bin";
    }
}
