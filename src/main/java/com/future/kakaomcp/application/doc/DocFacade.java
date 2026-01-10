package com.future.kakaomcp.application.doc;

import com.future.kakaomcp.api.doc.dto.SummarizeResponse;
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

        SummarizeMetadataContext ctx = buildSummarizeContext(normalized, enriched);
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
            meta.put("displayTitle", file != null ? file.getOriginalFilename() : null);
        }

        return SummarizeResponse.builder()
                .tool(McpToolName.SUMMARIZE_DOCUMENT)
                .mode(mode)
                .inputFileName(file != null ? file.getOriginalFilename() : null)
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

        SummarizeMetadataContext ctx = buildSummarizeContext(normalized, enriched);
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

    private SummarizeMetadataContext buildSummarizeContext(Map<String, Object> normalized, Map<String, Object> enriched) {
        Set<String> dois = new LinkedHashSet<>();
        Set<String> isbns = new LinkedHashSet<>();
        Set<String> authors = new LinkedHashSet<>();
        Set<String> emails = new LinkedHashSet<>();
        Set<String> affiliations = new LinkedHashSet<>();

        Object candidatesObj = normalized.get("identifierCandidates");
        if (candidatesObj instanceof Map<?, ?> candMap) {
            Object doiObj = candMap.get("doi");
            if (doiObj instanceof Collection<?> c) for (Object x : c) if (x != null) dois.add(String.valueOf(x).trim());

            Object isbnObj = candMap.get("isbn");
            if (isbnObj instanceof Collection<?> c) for (Object x : c) if (x != null) isbns.add(String.valueOf(x).trim());
        }

        Object mergedObj = enriched.get("merged");
        if (mergedObj instanceof Map<?, ?> merged) {
            Object doi = merged.get("doi");
            if (doi != null) dois.add(String.valueOf(doi).trim());

            Object isbn = merged.get("isbn");
            if (isbn != null) isbns.add(String.valueOf(isbn).trim());

            Object auth = merged.get("authors");
            if (auth instanceof String s && !s.isBlank()) {
                for (String token : s.split("[,;]")) {
                    String t = token.trim();
                    if (!t.isEmpty()) authors.add(t);
                }
            } else if (auth instanceof Collection<?> c) {
                for (Object x : c) {
                    if (x == null) continue;
                    String t = String.valueOf(x).trim();
                    if (!t.isEmpty()) authors.add(t);
                }
            }
        }

        Object authorObj = normalized.get("author");
        if (authorObj instanceof String s && !s.isBlank()) {
            for (String token : s.split("[,;]")) {
                String t = token.trim();
                if (!t.isEmpty()) authors.add(t);
            }
        }

        Object publisherObj = normalized.get("publisher");
        if (publisherObj instanceof String s && !s.isBlank()) affiliations.add(s.trim());

        return new SummarizeMetadataContext(authors, emails, dois, isbns, affiliations);
    }

    private Set<String> normalizeIdentifiersObject(Object identifiersFromMeta) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (identifiersFromMeta instanceof Collection<?> c) {
            for (Object x : c) {
                if (x == null) continue;
                String t = String.valueOf(x).trim();
                if (!t.isEmpty()) merged.add(t);
            }
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
