package com.future.kakaomcp.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({McpProperties.class})
public class McpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(McpClientConfig.class);

    @Bean
    public McpSyncClient mcpSyncClient(McpProperties props) {
        try {
            // 1) props 검증
            if (props.getArgs() == null || props.getArgs().isEmpty()) {
                throw new IllegalStateException("mcp.args가 비어있습니다. 예) args: [\"index.js\"]");
            }
            String cmd = (props.getCommand() == null || props.getCommand().isBlank()) ? "node" : props.getCommand().trim();

            // 2) workdir/env가 있으면 bash -lc 로 감싸서 강제 적용
            EffectiveExec exec = buildEffectiveExec(cmd, props.getArgs(), props.getWorkdir(), props.getEnv());

            log.info("[MCP] configured workdir={}", props.getWorkdir());
            log.info("[MCP] configured env keys={}", props.getEnv() == null ? List.of() : props.getEnv().keySet());
            log.info("[MCP] starting stdio server: command='{}', args={}", exec.command, exec.args);

            ServerParameters params = ServerParameters.builder(exec.command)
                    .args(exec.args)
                    .build();

            McpClientTransport transport =
                    new StdioClientTransport(params, new JacksonMcpJsonMapper(new ObjectMapper()));

            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(props.getRequestTimeoutSeconds()))
                    .capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
                    .build();

            client.initialize();
            log.info("[MCP] client initialized");
            return client;

        } catch (Exception e) {
            log.error("[MCP] client initialization failed", e);
            throw new IllegalStateException("MCP client 초기화 실패: " + e.getMessage(), e);
        }
    }

    private static EffectiveExec buildEffectiveExec(String command, List<String> args, String workdir, Map<String, String> env) {
        boolean hasWorkdir = workdir != null && !workdir.isBlank();
        boolean hasEnv = env != null && !env.isEmpty();

        // workdir/env가 없으면 그대로 실행 (node index.js)
        if (!hasWorkdir && !hasEnv) {
            return new EffectiveExec(command, args.toArray(new String[0]));
        }

        // bash -lc "cd workdir && export ... && exec node index.js"
        StringBuilder script = new StringBuilder();

        if (hasWorkdir) {
            script.append("cd ").append(shQuote(workdir.trim())).append(" && ");
        }
        if (hasEnv) {
            for (Map.Entry<String, String> e : env.entrySet()) {
                String k = e.getKey();
                if (k == null || k.isBlank()) continue;
                String v = (e.getValue() == null) ? "" : e.getValue();
                script.append("export ").append(k.trim()).append("=")
                        .append(shQuote(v)).append(" && ");
            }
        }

        script.append("exec ").append(shQuote(command));
        for (String a : args) {
            if (a == null || a.isBlank()) continue;
            script.append(" ").append(shQuote(a));
        }

        List<String> bashArgs = new ArrayList<>();
        bashArgs.add("-lc");
        bashArgs.add(script.toString());
        return new EffectiveExec("bash", bashArgs.toArray(new String[0]));
    }

    private static String shQuote(String s) {
        // bash single-quote: ' -> '"'"'
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static class EffectiveExec {
        final String command;
        final String[] args;
        EffectiveExec(String command, String[] args) {
            this.command = command;
            this.args = args;
        }
    }
}
