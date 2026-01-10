package com.future.kakaomcp.domain.doc.model;

import java.util.Map;

/**
 * 문서에서 추출된 텍스트 + 메타데이터(추후 Tika/Crossref/GoogleBooks enrich에 사용)
 */
public record ExtractedDocument(
        String text,
        Map<String, String> metadata
) {
}
