package com.future.kakaomcp.api.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.future.kakaomcp.api.doc.dto.SummarizeResponse;
import com.future.kakaomcp.application.doc.DocFacade;
import com.future.kakaomcp.application.doc.command.SummarizeCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.Base64;

import static com.future.kakaomcp.api.mcp.JsonRpcDto.*;

/**
 * MCP HTTP(JSON-RPC) endpoint
 * - tools/list
 * - tools/call: summarize_text / summarize_url / summarize_file (+ legacy summarize_document)
 *
 * NOTE:
 * - URL/File은 서버에 저장(/opt/kakaoMcp/uploads) 후 처리, TTL 1일
 * - PlayMCP 인식 안정화를 위해 요약 출력 Markdown 포맷을 "고정"한다.
 * - 기존 요약 엔진이 포함할 수 있는 "abstract/final/입력 길이/모드" 등 메타 라인을 strip 후 포맷팅한다.
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpHttpController {

    private static final ObjectMapper OM = new ObjectMapper();

    private static final String TOOL_SUMMARIZE_TEXT = "summarize_text";
    private static final String TOOL_SUMMARIZE_URL  = "summarize_url";
    private static final String TOOL_SUMMARIZE_FILE = "summarize_file";
    private static final String TOOL_LEGACY_SUMMARIZE_DOCUMENT = "summarize_document"; // 호환

    private static final long MAX_DOWNLOAD_BYTES = 25L * 1024L * 1024L; // 25MB
    private static final long MAX_FILE_BYTES     = 25L * 1024L * 1024L; // 25MB
    private static final int CONNECT_TIMEOUT_MS  = 10_000;
    private static final int READ_TIMEOUT_MS     = 60_000;
    private static final int MAX_REDIRECTS       = 8;

    private static final Path UPLOAD_DIR = Paths.get("/opt/kakaoMcp/uploads");
    private static final Duration TTL = Duration.ofDays(1);

    private final DocFacade docFacade;

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

        // 최소 SSE(단발성)
        SseEmitter emitter = new SseEmitter(30_000L);
        String json = OM.writeValueAsString(rpc);
        emitter.send(SseEmitter.event()
                .id(String.valueOf(Instant.now().toEpochMilli()))
                .name("message")
                .data(json, MediaType.APPLICATION_JSON));
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
        return Map.of(
                "protocolVersion", "2025-11-25",
                "serverInfo", Map.of("name", "kakaoMcp-spring", "version", "0.0.8"),
                "capabilities", Map.of("tools", Map.of())
        );
    }

    private Map<String, Object> toolsListResult() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_TEXT,
                "description", "텍스트를 Markdown 요약(고정 포맷)합니다.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "text", Map.of("type", "string", "description", "요약할 텍스트")
                        ),
                        "required", List.of("text")
                )
        ));

        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_URL,
                "description", "URL 문서를 다운로드 후 서버에 임시 저장하고 요약합니다. (TTL 1일)",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of("type", "string", "description", "요약할 문서 URL (http/https)")
                        ),
                        "required", List.of("url")
                )
        ));

        tools.add(Map.of(
                "name", TOOL_SUMMARIZE_FILE,
                "description", "base64 파일 업로드 → 서버 저장 → 요약합니다. (TTL 1일)",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "filename", Map.of("type", "string", "description", "원본 파일명"),
                                "contentBase64", Map.of("type", "string", "description", "base64 인코딩된 파일 내용")
                        ),
                        "required", List.of("filename", "contentBase64")
                )
        ));

        return Map.of("tools", tools);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolsCallResult(Map<String, Object> params) throws Exception {
        ensureUploadDir();
        cleanupExpiredUploads();

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
        return toolError("Unknown tool: " + name);
    }

    private Map<String, Object> callSummarizeText(Map<String, Object> args) {
        String text = String.valueOf(args.getOrDefault("text", ""));
        SummarizeResponse resp = docFacade.summarizeText(text, new SummarizeCommand("abstract", false));

        String md = formatSummary(
                resp.getSummaryText(),
                "text",
                Map.of()
        );
        return toolOk(md);
    }

    private Map<String, Object> callSummarizeUrl(Map<String, Object> args) throws Exception {
        String url = String.valueOf(args.getOrDefault("url", "")).trim();
        if (url.isBlank()) return toolError("url 파라미터가 비어 있습니다.");

        URLDownload d = download(url);
        if (d.bytes == null || d.bytes.length == 0) {
            return toolError("URL 다운로드 결과가 비어 있습니다.");
        }

        SavedFile saved = saveToUploads(d.fileName, d.bytes);

        MultipartFile mf = new InMemoryMultipartFile(d.fileName, d.contentType, d.bytes);
        SummarizeResponse resp = docFacade.summarizeFile(mf, new SummarizeCommand("abstract", false));

        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("fileId", saved.fileId());
        refs.put("storedFileName", saved.storedFileName());
        refs.put("expiresAtUtc", saved.expiresAtUtc());
        refs.put("sourceUrl", url);

        String md = formatSummary(resp.getSummaryText(), "url", refs);
        return toolOk(md);
    }

    private Map<String, Object> callSummarizeFile(Map<String, Object> args) throws Exception {
        String filename = String.valueOf(args.getOrDefault("filename", "upload.bin")).trim();
        String raw = String.valueOf(args.getOrDefault("contentBase64", "")).trim();
        if (raw.isBlank()) return toolError("contentBase64 파라미터가 비어 있습니다.");

        // data:...;base64,xxxx 형태 제거
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

        if (bytes.length > MAX_FILE_BYTES) {
            return toolError("파일이 너무 큽니다. 최대 " + (MAX_FILE_BYTES / (1024 * 1024)) + "MB 까지 가능합니다.");
        }

        SavedFile saved = saveToUploads(filename, bytes);

        MultipartFile mf = new InMemoryMultipartFile(filename, guessContentType(filename), bytes);
        SummarizeResponse resp = docFacade.summarizeFile(mf, new SummarizeCommand("abstract", false));

        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("fileId", saved.fileId());
        refs.put("storedFileName", saved.storedFileName());
        refs.put("expiresAtUtc", saved.expiresAtUtc());

        String md = formatSummary(resp.getSummaryText(), "file", refs);
        return toolOk(md);
    }

    // -------------------------
    // PlayMCP 인식용 "고정 포맷"
    // -------------------------

    private String formatSummary(String raw, String inputType, Map<String, Object> refs) {
        // ✅ 기존 요약 엔진이 포함하는 메타 라인을 제거한 뒤 포맷팅
        String body = stripMetaLines((raw == null) ? "" : raw.trim());

        String oneLine = extractOneLine(body);
        List<String> bullets = extractBullets(body);

        StringBuilder sb = new StringBuilder();
        sb.append("# 문서 요약\n\n");

        sb.append("## 한 줄 요약\n");
        if (!oneLine.isBlank()) sb.append("- ").append(oneLine).append("\n");
        else sb.append("- (요약 생성 결과가 비어 있습니다)\n");
        sb.append("\n");

        sb.append("## 핵심 포인트\n");
        if (!bullets.isEmpty()) {
            for (int i = 0; i < Math.min(7, bullets.size()); i++) {
                sb.append("- ").append(bullets.get(i)).append("\n");
            }
        } else if (!body.isBlank()) {
            sb.append("- ").append(truncate(cleanInline(stripHeadings(body)), 220)).append("\n");
        } else {
            sb.append("- (핵심 포인트를 추출할 수 없습니다)\n");
        }
        sb.append("\n");

        sb.append("## 참고\n");
        sb.append("- 입력: ").append(inputType).append("\n");
        sb.append("- 길이: ").append((raw == null) ? 0 : raw.length()).append(" chars\n");

        if (refs != null) {
            if (refs.get("sourceUrl") != null) sb.append("- URL: ").append(refs.get("sourceUrl")).append("\n");
            if (refs.get("fileId") != null) sb.append("- fileId: ").append(refs.get("fileId")).append("\n");
            if (refs.get("expiresAtUtc") != null) sb.append("- 만료(UTC): ").append(refs.get("expiresAtUtc")).append("\n");
            if (refs.get("storedFileName") != null) sb.append("- 저장 파일명: ").append(refs.get("storedFileName")).append("\n");
        }

        return sb.toString().trim() + "\n";
    }

    /**
     * 기존 요약 엔진이 생성하는 내부 메타(모드/입력길이/abstract 접두 등)를 제거한다.
     * - "## 모드", "- abstract", "- final"
     * - "- abstract - ..." 접두
     * - "- 입력 길이: 37 chars" 등
     */
    private String stripMetaLines(String s) {
        if (s == null) return "";
        String out = s;

        // "입력 길이: ..." 라인 제거
        out = out.replaceAll("(?m)^-\\s*입력\\s*길이\\s*:\\s*.*$", "");
        out = out.replaceAll("(?m)^입력\\s*길이\\s*:\\s*.*$", "");

        // "## 모드" 헤더 제거
        out = out.replaceAll("(?m)^##\\s*모드\\s*$", "");

        // 단독 모드 라인 제거
        out = out.replaceAll("(?m)^-\\s*(abstract|final)\\s*$", "");

        // "- abstract - ..." / "- final - ..." 접두 제거
        out = out.replaceAll("(?m)^-\\s*(abstract|final)\\s*-\\s*", "- ");

        // 잔여 공백 정리
        out = out.replaceAll("[ \\t]+", " ");
        out = out.replaceAll("(?m)^\\s+$", "");
        return out.trim();
    }

    private String extractOneLine(String md) {
        if (md == null) return "";
        String cleaned = stripHeadings(md).trim();
        cleaned = cleanInline(cleaned);

        if (cleaned.isBlank()) return "";

        String[] parts = cleaned.split("[\\r\\n]+");
        String first = parts[0].trim();
        if (first.startsWith("-")) first = first.replaceFirst("^[-*•]\\s*", "").trim();
        return truncate(first, 180);
    }

    private List<String> extractBullets(String md) {
        if (md == null) return List.of();
        List<String> out = new ArrayList<>();
        String[] lines = md.split("\\R");
        for (String line : lines) {
            String s = (line == null) ? "" : line.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("#")) continue;

            if (s.matches("^[-*•]\\s+.+")) {
                out.add(cleanInline(s.replaceFirst("^[-*•]\\s+", "")));
            } else if (s.matches("^\\d+\\.\\s+.+")) {
                out.add(cleanInline(s.replaceFirst("^\\d+\\.\\s+", "")));
            }
        }

        // 메타성 bullet 제거
        out.removeIf(x -> x == null || x.trim().length() < 8);
        out.removeIf(x -> {
            String t = x.trim().toLowerCase(Locale.ROOT);
            return t.startsWith("입력 길이")
                    || t.startsWith("모드")
                    || t.equals("abstract")
                    || t.equals("final");
        });

        return out;
    }

    private String stripHeadings(String s) {
        if (s == null) return "";
        // "# ..." 형태의 heading 라인을 제거
        return s.replaceAll("(?m)^#{1,6}\\s+.*$", "").trim();
    }

    private String cleanInline(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    // -------------------------
    // Tool response wrappers
    // -------------------------

    private Map<String, Object> toolOk(String text) {
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", text)),
                "isError", false
        );
    }

    private Map<String, Object> toolError(String text) {
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", text)),
                "isError", true
        );
    }

    // -------------------------
    // Upload store (TTL 1 day)
    // -------------------------

    private void ensureUploadDir() throws Exception {
        if (!Files.exists(UPLOAD_DIR)) Files.createDirectories(UPLOAD_DIR);
    }

    private void cleanupExpiredUploads() throws Exception {
        Instant cutoff = Instant.now().minus(TTL);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(UPLOAD_DIR)) {
            for (Path p : ds) {
                try {
                    FileTime ft = Files.getLastModifiedTime(p);
                    if (ft.toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(p);
                    }
                } catch (Exception ignore) {
                    // TTL 정리는 best-effort
                }
            }
        }
    }

    private SavedFile saveToUploads(String name, byte[] bytes) throws Exception {
        String id = UUID.randomUUID().toString();
        String stored = id + "__" + sanitize(name);
        Path p = UPLOAD_DIR.resolve(stored);

        Files.write(p, bytes, StandardOpenOption.CREATE_NEW);
        OffsetDateTime exp = OffsetDateTime.ofInstant(Instant.now().plus(TTL), ZoneOffset.UTC);
        return new SavedFile(id, stored, exp.toString());
    }

    private String sanitize(String s) {
        if (s == null || s.isBlank()) return "upload.bin";
        return s.replaceAll("[^a-zA-Z0-9가-힣._-]", "_");
    }

    private String guessContentType(String f) {
        if (f == null) return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String l = f.toLowerCase(Locale.ROOT);
        if (l.endsWith(".pdf")) return "application/pdf";
        if (l.endsWith(".epub")) return "application/epub+zip";
        if (l.endsWith(".txt")) return "text/plain";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    // -------------------------
    // URL download (redirect-safe)
    // -------------------------

    private URLDownload download(String rawUrl) throws Exception {
        String current = rawUrl;

        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "kakaoMcp/0.0.8");
            c.setRequestProperty("Accept", "*/*");

            int status = c.getResponseCode();

            if (status >= 300 && status < 400) {
                String loc = c.getHeaderField("Location");
                if (loc == null || loc.isBlank()) {
                    throw new IllegalStateException("리다이렉트 응답이지만 Location 헤더가 없습니다. status=" + status);
                }
                URL base = new URL(current);
                current = new URL(base, loc).toString();
                continue;
            }

            if (status < 200 || status >= 300) {
                throw new IllegalStateException("URL 다운로드 실패. status=" + status + ", url=" + current);
            }

            String ct = c.getContentType();
            long len = c.getContentLengthLong();
            if (len > MAX_DOWNLOAD_BYTES) {
                throw new IllegalStateException("URL 파일이 너무 큽니다. 최대 " + (MAX_DOWNLOAD_BYTES / (1024 * 1024)) + "MB 까지 가능합니다.");
            }

            try (InputStream in = c.getInputStream()) {
                byte[] b = readWithLimit(in, MAX_DOWNLOAD_BYTES);
                String name = guessNameFromUrlOrHeader(current, c);
                return new URLDownload(name, (ct == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : ct), b);
            }
        }

        throw new IllegalStateException("리다이렉트 횟수 초과: " + rawUrl);
    }

    private byte[] readWithLimit(InputStream in, long limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > limit) {
                throw new IllegalStateException("다운로드 용량 초과(>" + (limit / (1024 * 1024)) + "MB)");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private String guessNameFromUrlOrHeader(String url, HttpURLConnection c) {
        String cd = c.getHeaderField("Content-Disposition");
        if (cd != null) {
            String fn = extractFilenameFromContentDisposition(cd);
            if (fn != null && !fn.isBlank()) return sanitize(fn);
        }
        return guessName(url);
    }

    private String extractFilenameFromContentDisposition(String cd) {
        try {
            String lower = cd.toLowerCase(Locale.ROOT);
            int idx = lower.indexOf("filename*=");
            if (idx >= 0) {
                String part = cd.substring(idx + "filename*=".length()).trim();
                int q = part.indexOf("''");
                if (q >= 0) {
                    String v = part.substring(q + 2).trim();
                    v = v.replaceAll("^\"|\"$", "");
                    return v;
                }
            }
            idx = lower.indexOf("filename=");
            if (idx >= 0) {
                String part = cd.substring(idx + "filename=".length()).trim();
                int semi = part.indexOf(';');
                if (semi >= 0) part = part.substring(0, semi);
                part = part.trim().replaceAll("^\"|\"$", "");
                return part;
            }
        } catch (Exception ignore) {}
        return null;
    }

    private String guessName(String url) {
        if (url == null || url.isBlank()) return "download.bin";
        int q = url.indexOf('?');
        String u = (q >= 0) ? url.substring(0, q) : url;
        int i = u.lastIndexOf('/');
        String tail = (i >= 0) ? u.substring(i + 1) : u;
        if (tail.isBlank()) return "download.bin";
        if (!tail.contains(".")) return tail + ".bin";
        return tail;
    }

    private record URLDownload(String fileName, String contentType, byte[] bytes) {}
    private record SavedFile(String fileId, String storedFileName, String expiresAtUtc) {}

    // -------------------------
    // In-memory MultipartFile
    // -------------------------

    private static class InMemoryMultipartFile implements MultipartFile {
        private final String originalName;
        private final String contentType;
        private final byte[] bytes;

        InMemoryMultipartFile(String originalName, String contentType, byte[] bytes) {
            this.originalName = originalName;
            this.contentType = contentType;
            this.bytes = (bytes == null) ? new byte[0] : bytes;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return originalName; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }

        @Override
        public void transferTo(java.io.File dest) throws java.io.IOException {
            Files.write(dest.toPath(), bytes);
        }
    }
}
