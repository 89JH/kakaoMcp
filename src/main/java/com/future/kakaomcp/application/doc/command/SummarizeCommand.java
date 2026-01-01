package com.future.kakaomcp.application.doc.command;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record SummarizeCommand(
        @NotBlank String mode
) {}