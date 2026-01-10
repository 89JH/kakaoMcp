package com.future.kakaomcp.config;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({McpProperties.class, EnrichProperties.class})
public class McpClientConfig {

    @Bean
    public McpSyncClient mcpSyncClient(McpProperties props) {
        try {
            List<String> args = props.getArgs();
            if (args == null || args.isEmpty()) {
                throw new IllegalStateException("mcp.args가 비어있습니다. nodeMcp/index.js 절대경로를 넣어주세요.");
            }

            ServerParameters params = ServerParameters.builder(props.getCommand())
                    .args(args.toArray(String[]::new))
                    .build();

            McpClientTransport transport =
                    new StdioClientTransport(params, new JacksonMcpJsonMapper(new ObjectMapper()));

            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(props.getRequestTimeoutSeconds()))
                    .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
                    .build();

            client.initialize();
            return client;
        } catch (Exception e) {
            throw new IllegalStateException("MCP client 초기화 실패: " + e.getMessage(), e);
        }
    }
}
