package com.future.kakaomcp.domain.doc.exception;

import java.util.Map;

public class DrmProtectedEpubException extends RuntimeException {
    private final Map<String, String> extractedMetadata;

    public DrmProtectedEpubException(String message, Map<String, String> extractedMetadata) {
        super(message);
        this.extractedMetadata = extractedMetadata;
    }

    public Map<String, String> getExtractedMetadata() {
        return extractedMetadata;
    }
}
