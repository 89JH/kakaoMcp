package com.future.kakaomcp.application.doc;

import com.future.kakaomcp.api.doc.dto.SummarizeResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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

    public Object tools() {
        return mcpRelayService.listTools();
    }

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
}
