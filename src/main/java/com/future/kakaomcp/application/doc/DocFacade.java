package com.future.kakaomcp.application.doc;


import java.util.HashMap;
import java.util.Map;

import com.future.kakaomcp.api.doc.dto.SummarizeResponse;
import com.future.kakaomcp.application.doc.command.SummarizeCommand;
import com.future.kakaomcp.domain.file.TempFileService;
import com.future.kakaomcp.domain.file.model.TempStoredFile;
import com.future.kakaomcp.infrastructure.mcp.McpRelayService;
import com.future.kakaomcp.infrastructure.mcp.McpToolName;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class DocFacade {

    private final TempFileService tempFileService;
    private final McpRelayService mcpRelayService;

    public Object tools() {
        return mcpRelayService.listTools();
    }

    public SummarizeResponse summarize(MultipartFile file, SummarizeCommand command) {
        TempStoredFile stored = tempFileService.save(file);

        Map<String, Object> args = new HashMap<>();
        args.put("filePath", stored.path().toString());
        args.put("mode", command.mode()); // "abstract" 권장
        args.put("maxChars", 4000);

        CallToolResult result = mcpRelayService.callTool(McpToolName.SUMMARIZE_DOCUMENT, args);

        String summaryText = extractText(result);

        // 보안/저작권-safe: 처리 후 즉시 삭제
        tempFileService.deleteQuietly(stored.path());

        return SummarizeResponse.builder()
                .tool(McpToolName.SUMMARIZE_DOCUMENT)
                .mode(command.mode())
                .inputFileName(stored.originalFileName())
                .summaryText(summaryText)
                .meta(Map.of("rawResult", result))
                .build();
    }

    private String extractText(CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) return "";

        Object first = result.content().get(0);
        // 흔히 Map 형태로 들어오기도 해서 방어적으로 처리
        if (first instanceof java.util.Map<?,?> m) {
            Object text = m.get("text");
            return text == null ? String.valueOf(first) : String.valueOf(text);
        }
        return String.valueOf(first);
    }
}