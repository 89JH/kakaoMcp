package com.future.kakaomcp.application.doc;

import com.future.kakaomcp.api.doc.dto.SummarizeResponse;
import com.future.kakaomcp.api.mcp.McpToolProperties;
import com.future.kakaomcp.application.doc.SummarizeMetadataContext.SourceType;
import com.future.kakaomcp.application.doc.command.SummarizeCommand;
import com.future.kakaomcp.domain.doc.TextExtractService;
import com.future.kakaomcp.domain.doc.TextPreprocessor;
import com.future.kakaomcp.domain.doc.metadata.DocumentIdentifierExtractor;
import com.future.kakaomcp.domain.doc.metadata.MetadataNormalizer;
import com.future.kakaomcp.domain.doc.model.ExtractedDocument;
import com.future.kakaomcp.infrastructure.mcp.McpRelayService;
import com.future.kakaomcp.infrastructure.mcp.McpToolName;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DocFacade {

    private final TextExtractService textExtractService;
    private final McpRelayService mcpRelayService;
    private final McpSummarizationService summarizationService;
    private final MetadataEnricherService metadataEnricherService;
    private final McpToolProperties toolProperties;

    public Object tools() {
        return mcpRelayService.listTools();
    }

    // ---------------------------------------------------------------------
    // MCP(HttpController) 호환용 오버로드 (메서드/시그니처 변경 금지)
    // ---------------------------------------------------------------------

    /** MCP summarize_text */
    public Object summarizeText(String text, String mode, Boolean includeMeta) {
        return summarizeText(text, new SummarizeCommand(mode, includeMeta));
    }

    /** MCP summarize_file (base64 등 bytes 입력) */
    public Object summarizeBytes(byte[] bytes, String filename, String mode, Boolean includeMeta) {
        String safeName = (filename == null || filename.isBlank()) ? "upload.bin" : filename;
        MultipartFile mf = new ByteArrayMultipartFile("file", safeName, null, bytes == null ? new byte[0] : bytes);
        return summarizeFile(mf, new SummarizeCommand(mode, includeMeta));
    }

    /** MCP summarize_url (HTML 텍스트 추출 후 summarizeText 파이프라인 사용) */
    public Object summarizeUrl(String url, String mode, Boolean includeMeta) {
        String safeUrl = (url == null) ? "" : url.trim();
        if (safeUrl.isBlank()) {
            return SummarizeResponse.builder()
                    .tool(McpToolName.SUMMARIZE_DOCUMENT)
                    .mode(mode)
                    .inputFileName("(url)")
                    .summaryText("")
                    .meta(includeMeta != null && includeMeta ? Map.of("sourceUrl", safeUrl) : null)
                    .build();
        }

        String effectiveMode = (mode == null || mode.isBlank()) ? "abstract" : mode.trim();

        // ✅ URL은 요약 전에 "본문 텍스트"를 강하게 정제/축약해서 LLM 입력 토큰을 줄인다.
        // - 위키 같은 긴 문서도 1~2분 내 abstract가 나오도록, mode별로 상한을 다르게 적용
        String extractedText = fetchUrlAsText(safeUrl, effectiveMode);
        String textForSummarize = TextPreprocessor.preprocessForSummarize(extractedText);

        // URL은 메타데이터(doi/isbn)는 텍스트에서만 추출
        DocumentIdentifierExtractor.DetailedIdentifiers fromText =
                DocumentIdentifierExtractor.extractDetailedFromText(extractedText);

        List<String> doiCandidates = dedup(fromText.dois());
        List<String> isbnCandidates = dedup(fromText.isbns());

        LinkedHashSet<String> orderedIdentifiers = new LinkedHashSet<>();
        if (!doiCandidates.isEmpty()) orderedIdentifiers.add(doiCandidates.get(0));
        if (!isbnCandidates.isEmpty()) orderedIdentifiers.add(isbnCandidates.get(0));
        orderedIdentifiers.addAll(doiCandidates);
        orderedIdentifiers.addAll(isbnCandidates);

        Map<String, Object> enriched = metadataEnricherService.enrich(orderedIdentifiers);

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("identifiers", orderedIdentifiers);
        normalized.put("identifierCandidates", Map.of("doi", doiCandidates, "isbn", isbnCandidates));

        SummarizeMetadataContext ctx = buildSummarizeContext(
                SourceType.URL,
                safeUrl,
                "(url)",
                normalized,
                enriched
        );

        McpSummarizationService.Result sum = summarizationService.summarize(textForSummarize, effectiveMode, ctx);

        boolean wantMeta = includeMeta == null || includeMeta;
        Map<String, Object> meta = null;
        if (wantMeta) {
            meta = new LinkedHashMap<>();
            meta.put("sourceUrl", safeUrl);
            meta.put("normalizedMetadata", normalized);
            meta.put("enrichedMetadata", enriched);
            meta.put("summarizeMeta", sum.meta());
        }

        return SummarizeResponse.builder()
                .tool(McpToolName.SUMMARIZE_DOCUMENT)
                .mode(effectiveMode)
                .inputFileName(safeUrl)
                .summaryText(sum.summaryText())
                .meta(meta)
                .build();
    }

    // (중복 메서드 방지용) 아래쪽에 동일한 오버로드가 추가되지 않도록 주의

    public SummarizeResponse summarizeFile(MultipartFile file, SummarizeCommand command) {
        ExtractedDocument extracted = textExtractService.extract(file);

        String extractedText = extracted.text(); // 식별자 스캔용(원본)
        String textForSummarize = TextPreprocessor.preprocessForSummarize(extractedText); // 요약용(정리본)
        Map<String, String> tikaMetadata = extracted.metadata();

        // 1) normalizedMetadata
        Map<String, Object> normalized = MetadataNormalizer.normalize(tikaMetadata);

        // 2) identifiers: meta + text
        Set<String> metaIds = normalizeIdentifiersObject(normalized.get("identifiers"));

        DocumentIdentifierExtractor.DetailedIdentifiers fromText =
                DocumentIdentifierExtractor.extractDetailedFromText(extractedText);

        List<String> doiCandidates = new ArrayList<>();
        List<String> isbnCandidates = new ArrayList<>();

        for (String id : metaIds) {
            if (id == null) continue;
            String t = id.trim();
            if (t.isEmpty()) continue;

            if (t.toLowerCase(Locale.ROOT).startsWith("10.")) {
                doiCandidates.add(t.toLowerCase(Locale.ROOT));
            } else {
                String digits = t.replaceAll("[^0-9Xx]", "").toUpperCase(Locale.ROOT);
                if (digits.length() == 10 || digits.length() == 13) {
                    isbnCandidates.add(digits);
                }
            }
        }

        doiCandidates.addAll(fromText.dois());
        isbnCandidates.addAll(fromText.isbns());

        doiCandidates = dedup(doiCandidates);
        isbnCandidates = dedup(isbnCandidates);

        Map<String, Object> identifierCandidates = new LinkedHashMap<>();
        identifierCandidates.put("doi", doiCandidates);
        identifierCandidates.put("isbn", isbnCandidates);

        LinkedHashSet<String> orderedIdentifiers = new LinkedHashSet<>();
        if (!doiCandidates.isEmpty()) orderedIdentifiers.add(doiCandidates.get(0));
        if (!isbnCandidates.isEmpty()) orderedIdentifiers.add(isbnCandidates.get(0));
        orderedIdentifiers.addAll(doiCandidates);
        orderedIdentifiers.addAll(isbnCandidates);

        normalized.put("identifiers", orderedIdentifiers);
        normalized.put("identifierCandidates", identifierCandidates);

        // 3) enrich
        Map<String, Object> enriched = metadataEnricherService.enrich(orderedIdentifiers);

        // 4) summarize
        String mode = (command == null || command.mode() == null || command.mode().isBlank())
                ? "abstract" : command.mode().trim();

        String filename = (file != null ? file.getOriginalFilename() : null);

        SummarizeMetadataContext ctx = buildSummarizeContext(
                SourceType.FILE,
                null,
                filename,
                normalized,
                enriched
        );

        McpSummarizationService.Result sum = summarizationService.summarize(textForSummarize, mode, ctx);

        // ✅ includeMeta=false면 meta=null
        boolean includeMeta = (command == null) || command.includeMetaOrDefaultTrue();
        Map<String, Object> meta = null;

        if (includeMeta) {
            meta = new LinkedHashMap<>();
            meta.put("tikaMetadata", tikaMetadata);
            meta.put("normalizedMetadata", normalized);
            meta.put("enrichedMetadata", enriched);
            meta.put("summarizeMeta", sum.meta());
            meta.put("displayTitle", filename);
        }

        return SummarizeResponse.builder()
                .tool(McpToolName.SUMMARIZE_DOCUMENT)
                .mode(mode)
                .inputFileName(filename)
                .summaryText(sum.summaryText())
                .meta(meta)
                .build();
    }

    public SummarizeResponse summarizeText(String text, SummarizeCommand command) {
        String mode = (command == null || command.mode() == null || command.mode().isBlank())
                ? "abstract" : command.mode().trim();

        String textForSummarize = TextPreprocessor.preprocessForSummarize(text);

        DocumentIdentifierExtractor.DetailedIdentifiers fromText =
                DocumentIdentifierExtractor.extractDetailedFromText(text);

        List<String> doiCandidates = dedup(fromText.dois());
        List<String> isbnCandidates = dedup(fromText.isbns());

        LinkedHashSet<String> orderedIdentifiers = new LinkedHashSet<>();
        if (!doiCandidates.isEmpty()) orderedIdentifiers.add(doiCandidates.get(0));
        if (!isbnCandidates.isEmpty()) orderedIdentifiers.add(isbnCandidates.get(0));
        orderedIdentifiers.addAll(doiCandidates);
        orderedIdentifiers.addAll(isbnCandidates);

        Map<String, Object> enriched = metadataEnricherService.enrich(orderedIdentifiers);

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("identifiers", orderedIdentifiers);
        normalized.put("identifierCandidates", Map.of("doi", doiCandidates, "isbn", isbnCandidates));

        SummarizeMetadataContext ctx = buildSummarizeContext(
                SourceType.TEXT,
                null,
                "(text)",
                normalized,
                enriched
        );

        McpSummarizationService.Result sum = summarizationService.summarize(textForSummarize, mode, ctx);

        boolean includeMeta = (command == null) || command.includeMetaOrDefaultTrue();
        Map<String, Object> meta = null;

        if (includeMeta) {
            meta = new LinkedHashMap<>();
            meta.put("normalizedMetadata", normalized);
            meta.put("enrichedMetadata", enriched);
            meta.put("summarizeMeta", sum.meta());
        }

        return SummarizeResponse.builder()
                .tool(McpToolName.SUMMARIZE_DOCUMENT)
                .mode(mode)
                .inputFileName("(text)")
                .summaryText(sum.summaryText())
                .meta(meta)
                .build();
    }

    /**
     * normalized/enriched를 기반으로 SummarizeMetadataContext 구성
     * - record는 immutable이라 “Set을 모아서 생성자에 주입”하는 방식으로 사용
     */
    private SummarizeMetadataContext buildSummarizeContext(
            SourceType sourceType,
            String sourceUrl,
            String filename,
            Map<String, Object> normalized,
            Map<String, Object> enriched
    ) {
        Set<String> dois = new LinkedHashSet<>();
        Set<String> isbns = new LinkedHashSet<>();
        Set<String> authors = new LinkedHashSet<>();
        Set<String> emails = new LinkedHashSet<>();
        Set<String> affiliations = new LinkedHashSet<>();

        if (normalized == null) normalized = Map.of();
        if (enriched == null) enriched = Map.of();

        // ---- 1) DOI/ISBN 후보(normalized.identifierCandidates) ----
        Object candidatesObj = normalized.get("identifierCandidates");
        if (candidatesObj instanceof Map<?, ?> candMap) {
            addAllStringish(dois, candMap.get("doi"));
            addAllStringish(isbns, candMap.get("isbn"));
        }

        // ---- 2) enrich merged (doi/isbn/authors/affiliations/emails) ----
        Object mergedObj = enriched.get("merged");
        if (mergedObj instanceof Map<?, ?> merged) {
            addStringish(dois, merged.get("doi"));
            addStringish(isbns, merged.get("isbn"));

            Object auth = merged.get("authors");
            addPeopleLike(authors, auth);

            Object aff = merged.get("affiliations");
            addPeopleLike(affiliations, aff);

            Object em = merged.get("emails");
            addEmails(emails, em);
        }

        // ---- 3) normalized 쪽 author/creator/publisher 등 ----
        addPeopleLike(authors, normalized.get("author"));
        addPeopleLike(authors, normalized.get("creator"));
        addPeopleLike(authors, normalized.get("authors"));

        // affiliation 후보 (publisher/organization 등)
        addPeopleLike(affiliations, normalized.get("publisher"));
        addPeopleLike(affiliations, normalized.get("organization"));
        addPeopleLike(affiliations, normalized.get("affiliation"));

        // 이메일 후보
        addEmails(emails, normalized.get("email"));
        addEmails(emails, normalized.get("emails"));

        // ---- 4) 혹시 문자열 덩어리에서 이메일 패턴이 섞여 있으면 스캔 ----
        scanEmailsFromStrings(emails, authors);
        scanEmailsFromStrings(emails, affiliations);

        return new SummarizeMetadataContext(
                sourceType,
                sourceUrl,
                filename,
                emails,
                dois,
                isbns,
                authors,
                affiliations
        );
    }

    private void addStringish(Set<String> out, Object v) {
        if (v == null) return;
        String t = String.valueOf(v).trim();
        if (!t.isEmpty()) out.add(t);
    }

    private void addAllStringish(Set<String> out, Object v) {
        if (v == null) return;
        if (v instanceof Collection<?> c) {
            for (Object x : c) addStringish(out, x);
        } else {
            addStringish(out, v);
        }
    }

    /**
     * 사람이름/기관명 같이 "쉼표/세미콜론으로 나뉘는 값" 처리
     */
    private void addPeopleLike(Set<String> out, Object v) {
        if (v == null) return;

        if (v instanceof Collection<?> c) {
            for (Object x : c) addPeopleLike(out, x);
            return;
        }

        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return;

        // "a, b; c" 형태 지원
        String[] tokens = s.split("[,;]");
        for (String token : tokens) {
            String t = token.trim();
            if (!t.isEmpty()) out.add(t);
        }
    }

    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private void addEmails(Set<String> out, Object v) {
        if (v == null) return;

        if (v instanceof Collection<?> c) {
            for (Object x : c) addEmails(out, x);
            return;
        }

        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return;

        Matcher m = EMAIL.matcher(s);
        while (m.find()) {
            out.add(m.group().trim());
        }
    }

    private void scanEmailsFromStrings(Set<String> outEmails, Set<String> fromSet) {
        if (fromSet == null || fromSet.isEmpty()) return;
        for (String s : fromSet) {
            if (s == null) continue;
            Matcher m = EMAIL.matcher(s);
            while (m.find()) outEmails.add(m.group().trim());
        }
    }

    private Set<String> normalizeIdentifiersObject(Object identifiersFromMeta) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (identifiersFromMeta instanceof Collection<?> c) {
            for (Object x : c) {
                if (x == null) continue;
                String t = String.valueOf(x).trim();
                if (!t.isEmpty()) merged.add(t);
            }
        } else if (identifiersFromMeta != null) {
            String t = String.valueOf(identifiersFromMeta).trim();
            if (!t.isEmpty()) merged.add(t);
        }
        return merged;
    }

    private List<String> dedup(List<String> xs) {
        if (xs == null || xs.isEmpty()) return List.of();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String x : xs) {
            if (x == null) continue;
            String t = x.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return new ArrayList<>(set);
    }

    // ---------------------------------------------------------------------
    // URL -> text 추출 (타임아웃은 app.download.* 사용)
    // ---------------------------------------------------------------------
    private String fetchUrlAsText(String url, String mode) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(toolProperties.getDownload().getConnectTimeoutMs());
            conn.setReadTimeout(toolProperties.getDownload().getReadTimeoutMs());
            conn.setRequestProperty("User-Agent", toolProperties.getDownload().getUserAgent());
            conn.setRequestProperty("Accept-Encoding", "identity");

            int code = conn.getResponseCode();
            if (code >= 400) {
                throw new IllegalStateException("HTTP " + code);
            }

            String contentType = Optional.ofNullable(conn.getContentType()).orElse("");
            Charset cs = sniffCharset(contentType);

            try (InputStream in = conn.getInputStream()) {
                byte[] bytes = readAllLimited(in, (int) Math.min(Integer.MAX_VALUE, toolProperties.getDownload().getMaxBytes()));
                String raw = new String(bytes, cs);

                // HTML이면 Jsoup로 "중요 본문"만 뽑고, 길이를 강하게 제한해서 LLM 입력을 줄인다.
                if (contentType.toLowerCase(Locale.ROOT).contains("text/html") || raw.contains("<html")) {
                    return extractTextFromHtml(url, raw, mode);
                }

                // plain text 등도 길이 제한
                return clampLen(raw, urlTextLimit(mode));
            }

        } catch (Exception e) {
            throw new IllegalStateException("URL fetch failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * HTML -> "본문" 텍스트 추출
     * - Wikipedia 같은 페이지는 #mw-content-text 영역을 우선
     * - 문단(p) 기반으로 누적하면서 상한에 도달하면 즉시 중단
     */
    private String extractTextFromHtml(String url, String html, String mode) {
        int limit = urlTextLimit(mode);
        int minPara = Math.max(0, toolProperties.getUrl().getMinParagraphChars());
        int maxParas = Math.max(1, toolProperties.getUrl().getMaxParas());

        Document doc = Jsoup.parse(html, url);

        // 위키/미디어위키 계열이면 본문 영역을 강하게 좁힌다.
        boolean isWikipedia = url != null && url.contains("wikipedia.org");

        org.jsoup.nodes.Element root = doc.body();
        if (isWikipedia) {
            org.jsoup.nodes.Element mw = doc.selectFirst("#mw-content-text");
            if (mw != null) root = mw;

            // 불필요한 영역 제거(참고문헌/표/네비 등)
            root.select("table, .infobox, .navbox, .metadata, .reflist, ol.references, sup.reference, .mw-references-wrap, .toc").remove();
        } else {
            // 일반 페이지도 불필요한 스크립트/스타일 제거
            root.select("script, style, noscript").remove();
        }

        StringBuilder sb = new StringBuilder(Math.min(limit, 16_000));
        int paras = 0;

        // p 위주로 누적. 위키는 목록(li)도 의미가 있으니 같이 포함.
        for (org.jsoup.nodes.Element el : root.select(isWikipedia ? "p, li" : "p")) {
            if (paras >= maxParas) break;
            String t = el.text();
            if (t == null) continue;
            t = t.trim();
            if (t.length() < minPara) continue;

            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
            paras++;

            if (sb.length() >= limit) {
                break;
            }
        }

        String out = sb.toString();
        if (out.isBlank()) {
            // fallback: 전체 텍스트라도 제한 적용
            out = root.text();
        }
        return clampLen(out, limit);
    }

    /**
     * URL 본문 텍스트 상한
     * - abstract: 빠른 응답 우선(기본 12k)
     * - final: 조금 더 길게 허용(기본 18k)
     *
     * ※ toolProperties.url.maxUrlChars를 기본 상한으로 사용하되,
     *   위키 속도 최적화를 위해 mode별로 안전한 상한을 적용한다.
     */
    private int urlTextLimit(String mode) {
        int configured = Math.max(1000, toolProperties.getUrl().getMaxUrlChars());
        boolean isFinal = mode != null && mode.equalsIgnoreCase("final");

        // 목표: 위키 abstract 1~2분
        int fastAbstract = 12_000;
        int fastFinal = 18_000;

        int cap = isFinal ? fastFinal : fastAbstract;
        return Math.min(configured, cap);
    }

    private String clampLen(String s, int maxChars) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= maxChars) return t;
        return t.substring(0, maxChars);
    }

    private Charset sniffCharset(String contentType) {
        try {
            // e.g. text/html; charset=UTF-8
            String lower = (contentType == null) ? "" : contentType.toLowerCase(Locale.ROOT);
            int idx = lower.indexOf("charset=");
            if (idx >= 0) {
                String cs = contentType.substring(idx + "charset=".length()).trim();
                int semi = cs.indexOf(';');
                if (semi > 0) cs = cs.substring(0, semi);
                cs = cs.replace("\"", "").trim();
                if (!cs.isBlank()) return Charset.forName(cs);
            }
        } catch (Exception ignored) {}
        return StandardCharsets.UTF_8;
    }

    private byte[] readAllLimited(InputStream in, int maxBytes) throws Exception {
        byte[] buf = new byte[8192];
        int total = 0;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(Math.min(64 * 1024, maxBytes));
        int r;
        while ((r = in.read(buf)) != -1) {
            total += r;
            if (total > maxBytes) {
                throw new IllegalStateException("Downloaded content too large: " + total + " > " + maxBytes);
            }
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }

    /** spring-test 의존 없이 bytes로 MultipartFile 생성 */
    private static final class ByteArrayMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] bytes;

        private ByteArrayMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.bytes = bytes;
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes == null || bytes.length == 0; }
        @Override public long getSize() { return bytes == null ? 0 : bytes.length; }
        @Override public byte[] getBytes() { return bytes == null ? new byte[0] : bytes; }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(getBytes()); }
        @Override public void transferTo(java.io.File dest) throws java.io.IOException, IllegalStateException {
            java.nio.file.Files.write(dest.toPath(), getBytes());
        }
    }
}
