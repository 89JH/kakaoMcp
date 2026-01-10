package com.future.kakaomcp.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp")
public class McpProperties {
    private String command = "node";
    private List<String> args = new ArrayList<>();
    private long requestTimeoutSeconds = 60;

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }

    public long getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(long requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
}
