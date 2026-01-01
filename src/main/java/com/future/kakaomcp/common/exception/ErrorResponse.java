package com.future.kakaomcp.common.exception;

import lombok.Builder;

@Builder
public record ErrorResponse(
        String code,
        String message
) {}