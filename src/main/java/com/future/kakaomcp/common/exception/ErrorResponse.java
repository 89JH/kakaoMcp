package com.future.kakaomcp.common.exception;

import java.util.Map;
import lombok.Builder;

@Builder
public record ErrorResponse(
        String code,
        String message,
        Map<String, Object> meta
) {}
