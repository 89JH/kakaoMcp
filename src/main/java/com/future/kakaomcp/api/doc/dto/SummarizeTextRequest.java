package com.future.kakaomcp.api.doc.dto;

import jakarta.validation.constraints.NotBlank;

public record SummarizeTextRequest(
        @NotBlank String text,
        String mode
) {}
