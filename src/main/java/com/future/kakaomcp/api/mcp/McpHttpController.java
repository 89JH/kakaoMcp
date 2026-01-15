package com.future.kakaomcp.api.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.future.kakaomcp.api.doc.dto.SummarizeResponse;
import com.future.kakaomcp.application.doc.DocFacade;
import com.future.kakaomcp.application.doc.SummaryOutputCleaner;
import com.future.kakaomcp.application.doc.command.SummarizeCommand;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static com.future.kakaomcp.api.mcp.JsonRpcDto.*;

/**
 * MCP HTTP(JSON-RPC) endpoint
 * - initialize
 * - notifications/initialized
 * - tools/list
 * - tools/call: summarize_text / summarize_url / summarize_file (+ legacy summarize_document)
 *
 * 목표:
 * - protocolVersion 2025-03-26 버전 협상 반영 (서버 응답에 2025-03-26 반환)
 * - Streamable HTTP 방향: HTTP POST 기반 + (선택) SSE 단발 응답 지원
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpHttpController {

    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * MCP 2025-03-26 스펙에서 권장되는 content-type.
     * (Spring의 MediaType 상수에 없어서 문자열로 사용)
     */
    private static final String MCP_JSON = "application/mcp+json";

    private static final String TOOL_SUMMARIZE_TEXT = "summarize_text";
    private static final String TOOL_SUMMARIZE_URL  = "summarize_url";
    private static final String TOOL_SUMMARIZE_FILE = "summarize_file";
    private static final String TOOL_LEGACY_SUMMARIZE_DOCUMENT = "summarize_document";

    private static final Pattern SAFE_NAME = Pattern.compile("[^a-zA-Z0-9._-]+");

    private final DocFacade docFacade;
    private final McpToolProperties props; // 너희 프로젝트에 이미 존재하는 설정 빈

    @PostMapping(
            consumes = { MediaType.APPLICATION_JSON_VALUE, MCP_JSON },
            produces = { MediaType.APPLICATION_JSON_VALUE, MCP_JSON, MediaType.TEXT_EVENT_STREAM_VALUE }
    )
    public Object handle(
            @RequestBody Request req,
            @RequestHeader(value = "Accept", required = false) String accept
    ) throws Exception {

        boolean wantsSse = accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);

        Response rpc = dispatch(req);

        // 기본: JSON 응답
        if (!wantsSse) return rpc;

        // ✅ Streamable HTTP 최소 지원: “단발 SSE(1 이벤트)”로 응답
        // (나중에 토큰 스트리밍/다중 이벤트로 확장 가능)
        SseEmitter emitter = new SseEmitter(props.getSse().getTimeoutMs());
        emitter.send(SseEmitter.event()
                .id(String.valueOf(Instant.now().toEpochMilli()))
                .name("message")
                .data(rpc, MediaType.APPLICATION_JSON)
        );
        emitter.complete();
        return emitter;
    }

    private Response dispatch(Request req) {
        try {
            if (req == null || req.method == null || req.jsonrpc == null) {
                return Response.err(null, -32600, "Invalid Request", null);
            }

            return switch (req.method) {
                // ✅ 2025-03-26 lifecycle: initialize → initialized :contentReference[oaicite:1]{index=1}
                case "initialize" -> Response.ok(req.id, initializeResult(req));

                // ✅ initialize 이후 클라이언트가 보내는 알림(ready 신호) :contentReference[oaicite:2]{index=2}
                case "notifications/initialized" -> Response.ok(req.id, Map.of("ok", true));

                case "tools/list" -> Response.ok(req.id, toolsListResult());
                case "tools/call" -> Response.ok(req.id, toolsCallResult(req.params));
                default -> Response.err(req.id, -32601, "Method not found", null);
            };
        } catch (Exception e) {
            return Response.err(req == null ? null : req.id, -32603, "Internal error", Map.of("message", e.getMessage()));
        }
    }

    /**
     * 2025-03-26 Version Negotiation 규칙 반영 :contentReference[oaicite:3]{index=3}
     * - 클라이언트가 params.protocolVersion 으로 지원 버전을 보냄
     * - 서버가 지원하면 같은 버전으로 응답
     * - 미지원이면 서버가 지원하는 다른 버전(보통 최신)으로 응답
     */
    private Map<String, Object> initializeResult(Request req) {
        String requested = null;
        if (req != null && req.params != null && req.params.get("protocolVersion") != null) {
            requested = String.valueOf(req.params.get("protocolVersion")).trim();
        }

        // ✅ 서버가 지원하는 프로토콜 버전들 (최신 먼저)
        List<String> supported = List.of("2025-03-26");

        String negotiated = supported.get(0); // default: 최신
        if (requested != null && supported.contains(requested)) {
            negotiated = requested; // 지원하면 그대로 echo
        }

        // capabilities: 우리가 제공하는 것은 tools 중심이므로 tools만 최소 선언
        return Map.of(
                "protocolVersion", negotiated,
                "serverInfo", Map.of(
                        "name", "summaryDocuments",
                        "version", "0.1.0"
                ),
                "capabilities", Map.of(
                        "tools", Map.of("listChanged", false)
                )
        );
    }

    private Map<String, Object> toolsListResult() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_TEXT,
                "description", "텍스트를 요약합니다. (결과만 반환)",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "text", Map.of("type", "string", "description", "요약할 텍스트"),
                                "mode", Map.of("type", "string", "description", "abstract|final", "default", "abstract"),
                                "includeMeta", Map.of("type", "boolean", "default", false)
                        ),
                        "required", List.of("text")
                )
        ));

        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_URL,
                "description", "URL을 요약합니다. HTML은 본문 추출 후 요약하며, PDF/EPUB 등은 파일 파이프라인을 사용합니다. (결과만 반환)",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of("type", "string", "description", "요약할 URL"),
                                "mode", Map.of("type", "string", "description", "abstract|final", "default", "abstract"),
                                "includeMeta", Map.of("type", "boolean", "default", false)
                        ),
                        "required", List.of("url")
                )
        ));

        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_FILE,
                "description", "Base64로 전달된 파일(PDF/EPUB/TXT 등)을 요약합니다. (결과만 반환)",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "filename", Map.of("type", "string", "default", "upload.bin"),
                                "contentBase64", Map.of("type", "string", "description", "파일 base64 (data:...;base64, 가능)"),
                                "mode", Map.of("type", "string", "description", "abstract|final", "default", "abstract"),
                                "includeMeta", Map.of("type", "boolean", "default", false)
                        ),
                        "required", List.of("contentBase64")
                )
        ));

        tools.add(Map.of(
                "name", TOOL_LEGACY_SUMMARIZE_DOCUMENT,
                "description", "summarize_text와 동일(호환용)",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "text", Map.of("type", "string"),
                                "mode", Map.of("type", "string", "default", "abstract"),
                                "includeMeta", Map.of("type", "boolean", "default", false)
                        ),
                        "required", List.of("text")
                )
        ));

        return Map.of("tools", tools);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolsCallResult(Map<String, Object> params) throws Exception {
        if (params == null) return toolError("params 가 비어 있습니다.");

        String name = String.valueOf(params.get("name"));
        Map<String, Object> args = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        if (TOOL_SUMMARIZE_TEXT.equals(name) || TOOL_LEGACY_SUMMARIZE_DOCUMENT.equals(name)) {
            return callSummarizeText(args);
        }
        if (TOOL_SUMMARIZE_URL.equals(name)) {
            return callSummarizeUrl(args);
        }
        if (TOOL_SUMMARIZE_FILE.equals(name)) {
            return callSummarizeFile(args);
        }

        return toolError("지원하지 않는 tool 입니다: " + name);
    }

    private Map<String, Object> callSummarizeText(Map<String, Object> args) {
        String text = String.valueOf(args.getOrDefault("text", "")).trim();
        if (text.isBlank()) return toolError("text 파라미터가 비어 있습니다.");

        String mode = String.valueOf(args.getOrDefault("mode", "abstract"));
        boolean includeMeta = readBool(args.get("includeMeta"), false);

        SummarizeResponse resp = docFacade.summarizeText(text, new SummarizeCommand(mode, includeMeta));
        String clean = SummaryOutputCleaner.clean(resp.getSummaryText());

        return toolOk(clean);
    }

    private Map<String, Object> callSummarizeUrl(Map<String, Object> args) throws Exception {
        String url = String.valueOf(args.getOrDefault("url", "")).trim();
        if (url.isBlank()) return toolError("url 파라미터가 비어 있습니다.");

        String mode = String.valueOf(args.getOrDefault("mode", "abstract"));
        boolean includeMeta = readBool(args.get("includeMeta"), false);

        URLDownload d = download(url);
        if (d.bytes == null || d.bytes.length == 0) {
            return toolError("URL 다운로드 결과가 비어 있습니다.");
        }

        String ct = (d.contentType == null) ? "application/octet-stream" : d.contentType.toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(ct) || "application/octet-stream".equals(ct)) {
            ct = guessContentTypeByName(d.fileName);
        }

        // HTML → 본문 추출 후 요약
        if (ct.startsWith("text/html") || looksLikeHtml(d.bytes)) {
            Charset cs = guessCharset(ct);
            String html = new String(d.bytes, cs);
            String bodyText = extractMainTextFromHtml(url, html, 40, 35000);

            if (bodyText.isBlank()) {
                return toolError("HTML 본문 추출 결과가 비어 있습니다. (페이지 구조/길이 제한 영향)");
            }

            SummarizeResponse resp = docFacade.summarizeText(bodyText, new SummarizeCommand(mode, includeMeta));
            String clean = SummaryOutputCleaner.clean(resp.getSummaryText());
            return toolOk(clean);
        }

        // PDF/EPUB 등: 파일 파이프라인
        MultipartFile mf = new InMemoryMultipartFile(d.fileName, d.contentType, d.bytes);
        SummarizeResponse resp = docFacade.summarizeFile(mf, new SummarizeCommand(mode, includeMeta));
        String clean = SummaryOutputCleaner.clean(resp.getSummaryText());

        return toolOk(clean);
    }

    private Map<String, Object> callSummarizeFile(Map<String, Object> args) throws Exception {
        String filename = String.valueOf(args.getOrDefault("filename", "upload.bin")).trim();
        String raw = String.valueOf(args.getOrDefault("contentBase64", "")).trim();
        if (raw.isBlank()) return toolError("contentBase64 파라미터가 비어 있습니다.");

        String mode = String.valueOf(args.getOrDefault("mode", "abstract"));
        boolean includeMeta = readBool(args.get("includeMeta"), false);

        if (raw.startsWith("data:")) {
            int comma = raw.indexOf(',');
            if (comma >= 0) raw = raw.substring(comma + 1);
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            return toolError("contentBase64 디코딩 실패: base64 문자열을 확인해주세요.");
        }

        MultipartFile mf = new InMemoryMultipartFile(filename, guessContentTypeByName(filename), bytes);
        SummarizeResponse resp = docFacade.summarizeFile(mf, new SummarizeCommand(mode, includeMeta));
        String clean = SummaryOutputCleaner.clean(resp.getSummaryText());

        return toolOk(clean);
    }

    // -------------------------
    // MCP tool response shape
    // -------------------------
    private Map<String, Object> toolOk(String text) {
        return Map.of(
                "content", List.of(
                        Map.of("type", "text", "text", (text == null ? "" : text))
                )
        );
    }

    private Map<String, Object> toolError(String message) {
        return Map.of(
                "isError", true,
                "content", List.of(Map.of("type", "text", "text", "ERROR: " + message))
        );
    }

    private boolean readBool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) return def;
        return "true".equals(s) || "1".equals(s) || "y".equals(s) || "yes".equals(s);
    }

    // -------------------------
    // URL download helpers
    // -------------------------
    private record URLDownload(String fileName, String contentType, byte[] bytes) {}

    private URLDownload download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (kakao-mcp)");

        String contentType = Optional.ofNullable(conn.getContentType()).orElse("application/octet-stream");
        String fileName = guessFileName(url, conn);

        try (InputStream is = conn.getInputStream()) {
            byte[] bytes = readAll(is);
            return new URLDownload(fileName, contentType, bytes);
        }
    }

    private byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) >= 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private String guessFileName(String url, HttpURLConnection conn) {
        String cd = conn.getHeaderField("Content-Disposition");
        if (cd != null) {
            String lower = cd.toLowerCase(Locale.ROOT);
            int idx = lower.indexOf("filename=");
            if (idx >= 0) {
                String v = cd.substring(idx + "filename=".length()).trim().replace("\"", "");
                if (StringUtils.hasText(v)) return sanitizeName(v);
            }
        }
        try {
            String path = new URL(url).getPath();
            if (StringUtils.hasText(path)) {
                int slash = path.lastIndexOf('/');
                String name = (slash >= 0) ? path.substring(slash + 1) : path;
                if (StringUtils.hasText(name)) return sanitizeName(name);
            }
        } catch (Exception ignored) {}
        return "download.bin";
    }

    private String sanitizeName(String name) {
        String s = SAFE_NAME.matcher(name).replaceAll("_");
        if (s.length() > 120) s = s.substring(s.length() - 120);
        if (!StringUtils.hasText(s)) return "file.bin";
        return s;
    }

    private String guessContentTypeByName(String filename) {
        String lower = (filename == null ? "" : filename.toLowerCase(Locale.ROOT));
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".epub")) return "application/epub+zip";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        return "application/octet-stream";
    }

    private boolean looksLikeHtml(byte[] bytes) {
        if (bytes == null || bytes.length < 20) return false;
        String head = new String(bytes, 0, Math.min(bytes.length, 300), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return head.contains("<html") || head.contains("<!doctype html") || head.contains("<head") || head.contains("<body");
    }

    private Charset guessCharset(String contentTypeLower) {
        try {
            if (contentTypeLower == null) return StandardCharsets.UTF_8;
            String[] parts = contentTypeLower.split(";");
            for (String p : parts) {
                String t = p.trim();
                if (t.startsWith("charset=")) {
                    String cs = t.substring("charset=".length()).trim();
                    if (StringUtils.hasText(cs)) return Charset.forName(cs);
                }
            }
        } catch (Exception ignored) {}
        return StandardCharsets.UTF_8;
    }

    private String extractMainTextFromHtml(String url, String html, int maxParas, int maxChars) {
        Document doc = Jsoup.parse(html, url);
        doc.select("script, style, noscript, header, footer, nav, aside, form, iframe").remove();

        Element root = doc.selectFirst("article");
        if (root == null) root = doc.selectFirst("main");
        if (root == null) root = doc.body();
        if (root == null) return "";

        Elements paras = root.select("p, li");
        StringBuilder sb = new StringBuilder();
        int taken = 0;

        for (Element p : paras) {
            String t = p.text();
            if (t == null) continue;
            t = t.trim();
            if (t.isEmpty()) continue;

            if (sb.length() + t.length() + 1 > maxChars) break;
            sb.append(t).append('\n');
            taken++;
            if (taken >= maxParas) break;
        }

        return sb.toString().trim();
    }

    // -------------------------
    // In-memory MultipartFile
    // -------------------------
    private static class InMemoryMultipartFile implements MultipartFile {
        private final String fileName;
        private final String contentType;
        private final byte[] bytes;

        public InMemoryMultipartFile(String fileName, String contentType, byte[] bytes) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.bytes = bytes;
        }

        @Override public String getName() { return fileName; }
        @Override public String getOriginalFilename() { return fileName; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes == null || bytes.length == 0; }
        @Override public long getSize() { return bytes == null ? 0 : bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes == null ? new byte[0] : bytes); }
        @Override public void transferTo(File dest) throws IOException {
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(getBytes());
            }
        }
    }
}
