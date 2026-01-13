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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;

import static com.future.kakaomcp.api.mcp.JsonRpcDto.*;

/**
 * MCP HTTP(JSON-RPC) endpoint
 * - tools/list
 * - tools/call: summarize_text / summarize_url / summarize_file (+ legacy summarize_document)
 *
 * 목표:
 *  - tool 응답은 "요약 결과만" (CallToolResult[...] / 프롬프트 누출 방지)
 *  - summarize_url: HTML이면 jsoup 본문 추출 -> summarize_text 경로, PDF/EPUB는 file pipeline 유지
 *  - 제한값은 application.yml로 설정
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpHttpController {

    private static final ObjectMapper OM = new ObjectMapper();

    private static final String TOOL_SUMMARIZE_TEXT = "summarize_text";
    private static final String TOOL_SUMMARIZE_URL  = "summarize_url";
    private static final String TOOL_SUMMARIZE_FILE = "summarize_file";
    private static final String TOOL_LEGACY_SUMMARIZE_DOCUMENT = "summarize_document";

    // upload cleanup (기존 동작 유지)
    private static final String UPLOAD_DIR_NAME = "uploads";
    private static final String UPLOAD_PREFIX = "mcp_";
    private static final Pattern SAFE_NAME = Pattern.compile("[^a-zA-Z0-9._-]+");

    private final DocFacade docFacade;
    private final McpToolProperties props;

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = { MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE }
    )
    public Object handle(
            @RequestBody Request req,
            @RequestHeader(value = "Accept", required = false) String accept
    ) throws Exception {

        boolean wantsSse = accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        Response rpc = dispatch(req);

        if (!wantsSse) return rpc;

        // PlayMCP가 SSE accept 할 때를 위해 “단발 SSE”로 응답
        SseEmitter emitter = new SseEmitter(props.getSse().getTimeoutMs());
        String json = OM.writeValueAsString(rpc);
        emitter.send(SseEmitter.event()
                .id(String.valueOf(Instant.now().toEpochMilli()))
                .name("message")
                .data(json)
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
                case "initialize" -> Response.ok(req.id, initializeResult());
                case "tools/list" -> Response.ok(req.id, toolsListResult());
                case "tools/call" -> Response.ok(req.id, toolsCallResult(req.params));
                default -> Response.err(req.id, -32601, "Method not found", null);
            };
        } catch (Exception e) {
            return Response.err(req.id, -32603, "Internal error", Map.of("message", e.getMessage()));
        }
    }

    private Map<String, Object> initializeResult() {
        // 최소 initialize 응답
        return Map.of(
                "protocolVersion", "2024-11-05",
                "serverInfo", Map.of("name", "kakao-mcp-http", "version", "1.0.0"),
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

        // legacy alias
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
        ensureUploadDir();
        cleanupExpiredUploads();

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

        // ✅ tool은 “요약 결과만”
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
        // content-type이 애매한 경우 확장자로 보정
        if (!StringUtils.hasText(ct) || "application/octet-stream".equals(ct)) {
            ct = guessContentTypeByName(d.fileName);
        }

        // ✅ HTML: jsoup 본문 텍스트 추출 → summarizeText로 전달
        if (ct.startsWith("text/html") || looksLikeHtml(d.bytes)) {
            Charset cs = guessCharset(ct);
            String html = new String(d.bytes, cs);
            ExtractHtmlResult extracted = extractMainTextFromHtml(
                    url,
                    html,
                    props.getUrl().getMaxParas(),
                    props.getUrl().getMaxUrlChars()
            );
            String bodyText = extracted.text == null ? "" : extracted.text;

            if (bodyText.isBlank()) {
                return toolError("HTML 본문 추출 결과가 비어 있습니다. (maxUrlChars/maxParas 조정 필요)");
            }

            SummarizeResponse resp = docFacade.summarizeText(bodyText, new SummarizeCommand(mode, includeMeta));
            String clean = SummaryOutputCleaner.clean(resp.getSummaryText());
            return toolOk(clean);
        }

        // ✅ PDF/EPUB 등: 기존 file pipeline 유지
        if (d.bytes.length > props.getDownload().getMaxBytes()) {
            return toolError("다운로드 파일이 너무 큽니다. 최대 " + (props.getDownload().getMaxBytes() / (1024 * 1024)) + "MB");
        }

        SavedFile saved = saveToUploads(d.fileName, d.bytes);
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

        if (bytes.length > props.getFile().getMaxBytes()) {
            return toolError("파일이 너무 큽니다. 최대 " + (props.getFile().getMaxBytes() / (1024 * 1024)) + "MB");
        }

        SavedFile saved = saveToUploads(filename, bytes);
        MultipartFile mf = new InMemoryMultipartFile(filename, guessContentTypeByName(filename), bytes);

        SummarizeResponse resp = docFacade.summarizeFile(mf, new SummarizeCommand(mode, includeMeta));
        String clean = SummaryOutputCleaner.clean(resp.getSummaryText());
        return toolOk(clean);
    }

    // -------------------------
    // MCP tool response shape
    // -------------------------
    private Map<String, Object> toolOk(String text) {
        // MCP spec 형태: { content: [ { type:"text", text:"..." } ] }
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
    // URL download
    // -------------------------
    private record URLDownload(String fileName, String contentType, byte[] bytes) {}

    private URLDownload download(String url) throws Exception {
        String cur = url;
        int redirects = 0;

        while (true) {
            HttpURLConnection conn = (HttpURLConnection) new URL(cur).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(props.getDownload().getConnectTimeoutMs());
            conn.setReadTimeout(props.getDownload().getReadTimeoutMs());
            conn.setRequestProperty("User-Agent", props.getDownload().getUserAgent());

            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                if (loc == null || loc.isBlank()) break;
                redirects++;
                if (redirects > props.getDownload().getMaxRedirects()) throw new RuntimeException("Too many redirects");
                cur = resolveRedirect(cur, loc);
                continue;
            }

            String contentType = Optional.ofNullable(conn.getContentType()).orElse("application/octet-stream");
            String fileName = guessFileName(cur, conn);

            try (InputStream is = conn.getInputStream()) {
                byte[] bytes = readUpTo(is, props.getDownload().getMaxBytes());
                return new URLDownload(fileName, contentType, bytes);
            }
        }

        throw new RuntimeException("URL 다운로드 실패");
    }

    private String resolveRedirect(String base, String location) throws Exception {
        URL baseUrl = new URL(base);
        URL resolved = new URL(baseUrl, location);
        return resolved.toString();
    }

    private byte[] readUpTo(InputStream is, long maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = is.read(buf)) >= 0) {
            total += n;
            if (total > maxBytes) throw new IOException("download too large");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private String guessFileName(String url, HttpURLConnection conn) {
        // Content-Disposition 우선
        String cd = conn.getHeaderField("Content-Disposition");
        if (cd != null) {
            String lower = cd.toLowerCase(Locale.ROOT);
            int idx = lower.indexOf("filename=");
            if (idx >= 0) {
                String v = cd.substring(idx + "filename=".length()).trim();
                v = v.replace("\"", "");
                if (StringUtils.hasText(v)) return sanitizeName(v);
            }
        }
        // URL path fallback
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
            // e.g. text/html; charset=utf-8
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

    // -------------------------
    // HTML main text extraction
    // -------------------------
    private record ExtractHtmlResult(String title, String text) {}

    private ExtractHtmlResult extractMainTextFromHtml(String url, String html, int maxParas, int maxChars) {
        Document doc = Jsoup.parse(html, url);

        // 불필요 노드 제거
        doc.select("script, style, noscript, header, footer, nav, aside, form, iframe").remove();

        String title = "";
        try {
            title = Optional.ofNullable(doc.title()).orElse("");
        } catch (Exception ignored) {}

        // 1) article 우선
        Element root = doc.selectFirst("article");
        if (root == null) {
            // 2) main
            root = doc.selectFirst("main");
        }
        if (root == null) {
            // 3) body fallback
            root = doc.body();
        }

        if (root == null) return new ExtractHtmlResult(title, "");

        Elements paras = root.select("p, li");
        StringBuilder sb = new StringBuilder();
        int taken = 0;

        for (Element p : paras) {
            String t = p.text();
            if (t == null) continue;
            t = t.trim();
            if (t.isEmpty()) continue;

            // 너무 짧은 텍스트는 노이즈일 가능성 ↑
            if (t.length() < props.getUrl().getMinParagraphChars()) continue;

            if (sb.length() + t.length() + 1 > maxChars) break;

            sb.append(t).append('\n');
            taken++;
            if (taken >= maxParas) break;
        }

        String out = sb.toString().trim();
        return new ExtractHtmlResult(title, out);
    }

    // -------------------------
    // uploads (기존 동작 유지)
    // -------------------------
    private void ensureUploadDir() {
        File dir = new File(UPLOAD_DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
    }

    private void cleanupExpiredUploads() {
        File dir = new File(UPLOAD_DIR_NAME);
        if (!dir.exists()) return;

        long now = System.currentTimeMillis();
        long ttlMs = props.getUpload().getTtlMs();

        File[] files = dir.listFiles((d, n) -> n != null && n.startsWith(UPLOAD_PREFIX));
        if (files == null) return;

        for (File f : files) {
            long age = now - f.lastModified();
            if (age > ttlMs) {
                try { f.delete(); } catch (Exception ignored) {}
            }
        }
    }

    private record SavedFile(String fileId, String storedFileName, String expiresAtUtc) {}

    private SavedFile saveToUploads(String originalName, byte[] bytes) throws IOException {
        String id = UUID.randomUUID().toString().replace("-", "");
        String safe = sanitizeName(originalName);
        String stored = UPLOAD_PREFIX + id + "_" + safe;

        File dir = new File(UPLOAD_DIR_NAME);
        File out = new File(dir, stored);

        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bytes);
        }

        OffsetDateTime expires = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(props.getUpload().getTtlMs() / 1000);
        return new SavedFile(id, stored, expires.toString());
    }

    // -------------------------
    // in-memory MultipartFile
    // -------------------------
    private static class InMemoryMultipartFile implements MultipartFile {
        private final String fileName;
        private final String contentType;
        private final byte[] bytes;

        InMemoryMultipartFile(String fileName, String contentType, byte[] bytes) {
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
