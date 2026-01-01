package com.future.kakaomcp.domain.file.model;


import java.nio.file.Path;
import java.time.Instant;

import lombok.Builder;

@Builder
public record TempStoredFile(
        String originalFileName,
        Path path,
        Instant createdAt
) {}