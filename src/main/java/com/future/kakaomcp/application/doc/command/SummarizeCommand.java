package com.future.kakaomcp.application.doc.command;

/**
 * summarize 요청 파라미터를 담는 커맨드.
 */
public record SummarizeCommand(
        String mode,
        Boolean includeMeta
) {
    public boolean includeMetaOrDefaultTrue() {
        return includeMeta == null || includeMeta;
    }
}
