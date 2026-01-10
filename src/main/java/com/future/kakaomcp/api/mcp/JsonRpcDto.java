package com.future.kakaomcp.api.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

public class JsonRpcDto {

    @Getter @Setter
    public static class Request {
        public String jsonrpc;  // "2.0"
        public Object id;       // number|string|null
        public String method;
        public Map<String, Object> params;
    }

    @Getter @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Response {
        public String jsonrpc = "2.0";
        public Object id;
        public Object result;
        public Error error;

        public static Response ok(Object id, Object result) {
            Response r = new Response();
            r.id = id;
            r.result = result;
            return r;
        }

        public static Response err(Object id, int code, String message, Object data) {
            Response r = new Response();
            r.id = id;
            r.error = new Error(code, message, data);
            return r;
        }
    }

    @Getter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Error {
        public int code;
        public String message;
        public Object data;
    }
}
