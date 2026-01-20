package com.future.kakaomcp.api.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.future.kakaomcp.api.doc.dto.SummarizeResponse;
import com.future.kakaomcp.application.doc.DocFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.*;

import static com.future.kakaomcp.api.mcp.JsonRpcDto.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mcp")
public class McpHttpController {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String JSONRPC = "2.0";

    private static final String PROTOCOL_LATEST = "2025-03-26";
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of(
            "2025-03-26"
            // 필요 없으면 2024-11-05는 빼도 됨
            // ,"2024-11-05"
    );

    private static final String TOOL_SUMMARIZE_TEXT = "summarize_text";
    private static final String TOOL_SUMMARIZE_URL = "summarize_url";
    private static final String TOOL_SUMMARIZE_FILE = "summarize_file";
    private static final String TOOL_SUMMARIZE_DOCUMENT = "summarize_document"; // legacy alias

    private final DocFacade docFacade;
    private final McpToolProperties toolProperties;

    // -------------------------
    // Streamable HTTP (SSE)
    // -------------------------
    @PostMapping(
            consumes = { MediaType.APPLICATION_JSON_VALUE, "application/mcp+json" },
            produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE }
    )
    public ResponseEntity<?> handle(@RequestHeader Map<String, String> headers,
                                    @RequestBody String rawBody) {

        boolean wantSse = headers.entrySet().stream()
                .anyMatch(e -> "accept".equalsIgnoreCase(e.getKey()) &&
                        e.getValue() != null &&
                        e.getValue().toLowerCase(Locale.ROOT).contains("text/event-stream"));

        Map<String, Object> req;
        try {
            req = OM.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception e) {
            return ResponseEntity.ok(error(null, -32700, "Parse error", Map.of("message", e.getMessage())));
        }

        String method = asString(req.get("method"));
        Object id = req.get("id");
        Map<String, Object> params = asMap(req.get("params"));

        if (wantSse) {
            // Streamable HTTP: single message event
            String eventId = "id:" + Instant.now().toEpochMilli();
            Map<String, Object> out = dispatch(method, id, params);

            String ssePayload = toSse(eventId, "message", out);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(ssePayload);
        }

        Map<String, Object> out = dispatch(method, id, params);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(out);
    }

    private Map<String, Object> dispatch(String method, Object id, Map<String, Object> params) {
        try {
            if ("initialize".equals(method)) {
                return handleInitialize(id, params);
            }
            if ("notifications/initialized".equals(method)) {
                return okResult(id, Map.of("ok", true));
            }
            if ("tools/list".equals(method)) {
                return handleToolsList(id);
            }
            if ("tools/call".equals(method)) {
                return handleToolsCall(id, params);
            }
            return error(id, -32601, "Method not found", Map.of("method", method));
        } catch (Exception e) {
            log.error("MCP error", e);
            return error(id, -32603, "Internal error", Map.of("message", e.getMessage()));
        }
    }

    // -------------------------
    // initialize
    // -------------------------
    private Map<String, Object> handleInitialize(Object id, Map<String, Object> params) {
        String requested = null;
        if (params != null) {
            requested = asString(params.get("protocolVersion"));
        }

        String chosen;
        if (requested == null || requested.isBlank()) {
            chosen = PROTOCOL_LATEST;
        } else if (SUPPORTED_PROTOCOLS.contains(requested)) {
            chosen = requested;
        } else {
            // unknown version -> choose latest to be forward-compatible
            chosen = PROTOCOL_LATEST;
        }

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

        // summarize_text (default=final)
        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_TEXT,
                "description", "텍스트를 요약합니다. (결과만 반환)",
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

        // summarize_url (default=abstract)
        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_URL,
                "description", "URL을 요약합니다. HTML은 본문 추출 후 요약하며, PDF/EPUB 등은 파일 파이프라인을 사용합니다. (결과만 반환)",
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

        // summarize_file (default=final)
        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_FILE,
                "description", "Base64로 전달된 파일(PDF/EPUB/TXT 등)을 요약합니다. (결과만 반환)",
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

        // legacy alias (optional)
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
    // tools/call
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
                // ✅ FIX: checked exception(throws Exception) 처리
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

        // PDF/EPUB 등 파일 URL이면 파일 파이프라인으로
        if (looksLikeFileUrl(url)) {
            URLDownload d = download(url);
            Object result = docFacade.summarizeBytes(d.bytes, d.filename, mode, includeMeta);
            String out = extractText(result);
            return okContentText(id, out);
        }

        // HTML 등 텍스트 URL
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
    // helpers
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

    private static String toSse(String idLine, String event, Map<String, Object> payload) {
        String json;
        try {
            json = OM.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            json = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
        }
        return idLine + "\n" +
                "event:" + event + "\n" +
                "data:" + json + "\n\n";
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
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
        if (s.startsWith("data:") && comma > 0) {
            s = s.substring(comma + 1);
        }
        return Base64.getDecoder().decode(s);
    }

    /**
     * ✅ MCP content로 내려줄 "문자열"만 추출
     * - SummarizeResponse -> summaryText
     * - Map -> summaryText 또는 content[0].text
     * - String -> 그대로
     */
    private static String extractText(Object result) {
        if (result == null) return "";
        if (result instanceof String s) return s;

        if (result instanceof SummarizeResponse sr) {
            return sr.getSummaryText() == null ? "" : sr.getSummaryText();
        }

        if (result instanceof Map<?, ?> m) {
            Object summaryText = m.get("summaryText");
            if (summaryText instanceof String s) return s;

            // 혹시 이미 MCP 포맷(content 배열) 형태라면
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

    // URL download helpers
    private record URLDownload(byte[] bytes, String filename) {}

    private URLDownload download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);

        // ✅ timeout은 properties/application.yml로 제어
        conn.setConnectTimeout(toolProperties.getDownload().getConnectTimeoutMs());
        conn.setReadTimeout(toolProperties.getDownload().getReadTimeoutMs());

        conn.setRequestProperty("User-Agent", toolProperties.getDownload().getUserAgent());
        conn.setRequestProperty("Accept-Encoding", "identity");

        int code = conn.getResponseCode();
        if (code >= 400) {
            throw new IOException("HTTP " + code);
        }

        String contentType = Optional.ofNullable(conn.getContentType()).orElse("");
        String disp = Optional.ofNullable(conn.getHeaderField("Content-Disposition")).orElse("");
        String filename = guessFilename(url, disp);

        byte[] bytes;
        try (InputStream in = conn.getInputStream()) {
            bytes = readAllLimited(in, toolProperties.getDownload().getMaxBytes());
        }

        log.debug("download ok: url={}, type={}, bytes={}", url, contentType, bytes.length);
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
            if (total > maxBytes) {
                throw new IOException("Downloaded file too large: " + total + " > " + maxBytes);
            }
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }

    private static String guessFilename(String url, String contentDisposition) {
        String lower = contentDisposition.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("filename=");
        if (idx >= 0) {
            String v = contentDisposition.substring(idx + "filename=".length()).trim();
            v = v.replace("\"", "");
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
