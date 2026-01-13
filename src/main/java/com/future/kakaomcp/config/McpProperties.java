package com.future.kakaomcp.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    /** 실행 커맨드 (기본 node) */
    private String command = "node";

    /** 예) ["index.js"] 또는 ["/opt/kakaoMcp/nodeMcp/index.js"] */
    private List<String> args = new ArrayList<>();

    /** Node 실행 workdir (예: /opt/kakaoMcp/nodeMcp) */
    private String workdir;

    /** Node 프로세스 env (예: OLLAMA_BASE, OLLAMA_MODEL 등) */
    private Map<String, String> env = new LinkedHashMap<>();

    /** MCP 요청 타임아웃(초) */
    private long requestTimeoutSeconds = 60;

    // ---- 기존 getter/setter 유지 ----
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }

    public long getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(long requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }

    // ---- 추가된 getter/setter ----
    public String getWorkdir() { return workdir; }
    public void setWorkdir(String workdir) { this.workdir = workdir; }

    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env; }
}
