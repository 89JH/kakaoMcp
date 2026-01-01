package com.future.kakaomcp.infrastructure.mcp;


import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class McpRelayService {

    private final McpSyncClient client;

    public Object listTools() {
        return client.listTools();
    }

    public CallToolResult callTool(String toolName, Map<String, Object> args) {
        return client.callTool(new CallToolRequest(toolName, args));
    }
}