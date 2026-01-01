package com.future.kakaomcp.api.doc.dto;

import java.util.Map;

import lombok.Builder;

@Builder
public record SummarizeResponse(
        String tool,
        String mode,
        String inputFileName,
        String summaryText,
        Map<String, Object> meta
) {}