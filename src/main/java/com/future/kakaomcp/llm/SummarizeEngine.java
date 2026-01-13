package com.future.kakaomcp.llm;

public interface SummarizeEngine {
    String summarize(String text, String mode, int maxChars);
}
