package com.future.kakaomcp.infrastructure.mcp;

import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class McpRelayService {

    private final McpSyncClient client;

    public Object listTools() {
        return client.listTools();
    }

    public McpSchema.CallToolResult callTool(String toolName, Map<String, Object> args) {
        McpSchema.CallToolRequest req = new McpSchema.CallToolRequest(toolName, args);
        return client.callTool(req);
    }
}
