package com.future.kakaomcp.domain.doc;

import com.future.kakaomcp.domain.doc.exception.DrmProtectedEpubException;
import com.future.kakaomcp.domain.doc.model.ExtractedDocument;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.ContentHandler;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class TextExtractService {

    private static final int MAX_EXTRACT_CHARS = 300_000;

    public ExtractedDocument extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new ExtractedDocument("", Map.of());
        }

        // ✅ EPUB DRM 감지
        if (isEpub(file) && isDrmEpub(file)) {
            // DRM이어도 OPF 메타는 평문인 경우가 많아서 추출해준다(복호화 아님)
            Map<String, String> opfMeta = extractOpfMetadataBestEffort(file);

            // 내부 플래그(정규화 시 참고 가능)
            opfMeta.put("x:drmProtected", "true");
            opfMeta.putIfAbsent("Content-Type", "application/epub+zip");

            // ✅ 422로 안내 (요약 불가)
            throw new DrmProtectedEpubException(
                    "DRM 보호 EPUB(Adobe)입니다. 본문 추출/요약이 불가합니다. " +
                            "Adobe Digital Editions에서 'DRM 없는 EPUB/PDF'로 제공되는 파일만 업로드해주세요.",
                    opfMeta
            );
        }

        // ✅ 비DRM: 기존 Tika 파싱
        try (InputStream is = file.getInputStream()) {
            AutoDetectParser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();

            ContentHandler handler = new BodyContentHandler(MAX_EXTRACT_CHARS);
            ParseContext context = new ParseContext();
            parser.parse(is, handler, metadata, context);

            String text = normalizeText(handler.toString());
            Map<String, String> metaMap = toMap(metadata);

            // Content-Type이 비어있으면 보강
            metaMap.putIfAbsent("Content-Type", file.getContentType() != null ? file.getContentType() : "");

            return new ExtractedDocument(text, metaMap);
        } catch (Exception e) {
            throw new IllegalStateException("텍스트/메타데이터 추출 실패: " + e.getMessage(), e);
        }
    }


    private boolean isEpub(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null && name.toLowerCase().endsWith(".epub")) return true;
        String ct = file.getContentType();
        return ct != null && ct.toLowerCase().contains("epub");
    }

    /**
     * DRM EPUB 판별: META-INF/encryption.xml 존재 여부
     */
    private boolean isDrmEpub(MultipartFile file) {
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName();
                if ("META-INF/encryption.xml".equalsIgnoreCase(n)) return true;
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    /**
     * DRM이어도 OPF 메타는 평문인 경우가 많으므로 best-effort로 뽑는다.
     * (복호화/우회가 아님)
     */
    private Map<String, String> extractOpfMetadataBestEffort(MultipartFile file) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("Content-Type", "application/epub+zip");

        try {
            // 1) META-INF/container.xml 읽어서 OPF 경로 찾기
            String containerXml = readZipEntryAsString(file, "META-INF/container.xml");
            if (containerXml == null) return out;

            String opfPath = findOpfPathFromContainer(containerXml);
            if (opfPath == null) return out;

            // 2) OPF 파일 읽기
            String opfXml = readZipEntryAsString(file, opfPath);
            if (opfXml == null) return out;

            // 3) OPF에서 dc:* 추출
            parseDcFieldsFromOpf(opfXml, out);

        } catch (Exception ignore) {
            // best-effort: 실패해도 out만 반환
        }

        return out;
    }

    private String readZipEntryAsString(MultipartFile file, String entryName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (entryName.equals(e.getName())) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private String findOpfPathFromContainer(String containerXml) {
        try {
            Document doc = parseXml(containerXml);
            // rootfile @full-path
            NodeList rootfiles = doc.getElementsByTagName("rootfile");
            if (rootfiles.getLength() == 0) return null;
            var node = rootfiles.item(0);
            var attrs = node.getAttributes();
            if (attrs == null) return null;
            var fullPath = attrs.getNamedItem("full-path");
            if (fullPath == null) return null;
            return fullPath.getNodeValue();
        } catch (Exception e) {
            return null;
        }
    }

    private void parseDcFieldsFromOpf(String opfXml, Map<String, String> out) {
        try {
            Document doc = parseXml(opfXml);

            // namespace-aware로 dc:* 태그를 직접 조회하기 어렵다면 localName 기준으로 추출
            putFirstTextByLocalName(doc, out, "dc:title", "title");
            putFirstTextByLocalName(doc, out, "dc:creator", "creator");
            putFirstTextByLocalName(doc, out, "dc:publisher", "publisher");
            putFirstTextByLocalName(doc, out, "dc:date", "date");
            putFirstTextByLocalName(doc, out, "dc:language", "language");
            putFirstTextByLocalName(doc, out, "dc:identifier", "identifier");
        } catch (Exception ignore) {
        }
    }

    private void putFirstTextByLocalName(Document doc, Map<String, String> out, String key, String localName) {
        NodeList list = doc.getElementsByTagNameNS("*", localName);
        if (list.getLength() > 0) {
            String v = list.item(0).getTextContent();
            if (v != null && !v.trim().isEmpty()) out.put(key, v.trim());
        }
    }

    private Document parseXml(String xml) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // XXE 방지(안전)
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignore) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignore) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignore) {}
        try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignore) {}

        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, String> toMap(Metadata metadata) {
        Map<String, String> out = new LinkedHashMap<>();
        if (metadata == null) return out;

        for (String name : metadata.names()) {
            String v = metadata.get(name);
            if (v == null) continue;
            String t = v.trim();
            if (!t.isEmpty()) out.put(name, t);
        }
        return out;
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text
                .replace("\u0000", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
