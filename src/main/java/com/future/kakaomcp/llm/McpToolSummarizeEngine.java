package com.future.kakaomcp.llm;

import com.future.kakaomcp.infrastructure.mcp.McpRelayService;
import com.future.kakaomcp.infrastructure.mcp.McpToolName;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class McpToolSummarizeEngine implements SummarizeEngine {

    private final McpRelayService mcpRelayService;

    @Override
    public String summarize(String text, String mode, int maxChars) {
        McpSchema.CallToolResult result = mcpRelayService.callTool(
                McpToolName.SUMMARIZE_DOCUMENT,
                Map.of(
                        "text", text,
                        "mode", mode,
                        "maxChars", maxChars
                )
        );
        return extractTextFromResult(result);
    }

    private String extractTextFromResult(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "";
        }
        Object first = result.content().get(0);
        if (first instanceof McpSchema.TextContent tc) {
            return tc.text() == null ? "" : tc.text();
        }
        return first == null ? "" : String.valueOf(first);
    }
}
