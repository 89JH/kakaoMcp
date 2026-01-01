import fs from "node:fs";
import path from "node:path";

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
    CallToolRequestSchema,
    ListToolsRequestSchema
} from "@modelcontextprotocol/sdk/types.js";

function readTextFile(filePath, maxChars = 20000) {
    const ext = path.extname(filePath).toLowerCase();
    if (![".txt", ".md"].includes(ext)) {
        throw new Error(`MVP는 .txt/.md만 지원합니다. (${ext})`);
    }
    const raw = fs.readFileSync(filePath, "utf8");
    return raw.slice(0, maxChars);
}

function abstractSummary(text, maxChars = 4000) {
    const lines = text.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
    const title = (lines[0] ?? "Untitled").slice(0, 120);
    const bullets = lines.slice(1, 40).map(l => `- ${l.slice(0, 160)}`);

    return [
        "# 요약",
        "## 제목(추정)",
        title,
        "",
        "## 핵심 포인트(재서술 요약)",
        ...bullets.slice(0, 10),
    ].join("\n").slice(0, maxChars);
}

// ✅ 로우레벨 Server (가장 호환성 좋음)
const server = new Server(
    { name: "kakao-mcp", version: "0.0.1" },
    { capabilities: { tools: {} } }
);

// ✅ tools/list: 스키마를 명확히 내려줌
server.setRequestHandler(ListToolsRequestSchema, async () => {
    return {
        tools: [
            {
                name: "summarize_document",
                description: "Summarize a local text/markdown file (safe abstract summary)",
                inputSchema: {
                    type: "object",
                    properties: {
                        filePath: { type: "string", description: "서버가 읽을 로컬 파일 경로(.txt/.md)" },
                        mode: { type: "string", description: "abstract(권장)", default: "abstract" },
                        maxChars: { type: "number", description: "요약 최대 길이", default: 4000 }
                    },
                    required: ["filePath"]
                }
            }
        ]
    };
});

// ✅ tools/call: args는 request.params.input (또는 arguments)로 들어옴
server.setRequestHandler(CallToolRequestSchema, async (request) => {
    try {
        if (request.params.name !== "summarize_document") {
            return {
                isError: true,
                content: [{ type: "text", text: `Unknown tool: ${request.params.name}` }]
            };
        }

        const args = request.params.input ?? request.params.arguments ?? {}; // 스펙/구현 차이 방어 :contentReference[oaicite:2]{index=2}
        const filePath = args.filePath;
        const mode = args.mode ?? "abstract";
        const maxChars = args.maxChars ?? 4000;

        if (!filePath || typeof filePath !== "string") {
            return {
                isError: true,
                content: [{ type: "text", text: `filePath missing. got args=${JSON.stringify(args)}` }]
            };
        }

        const text = readTextFile(filePath, 20000);
        const summary = abstractSummary(text, maxChars);

        return {
            content: [{ type: "text", text: summary }]
        };
    } catch (e) {
        console.error("Tool error:", e); // stderr only
        return {
            isError: true,
            content: [{ type: "text", text: e?.message ? String(e.message) : "Unknown error" }]
        };
    }
});

// ✅ stdio 연결
await server.connect(new StdioServerTransport());
console.error("[kakao-mcp] server started (stderr)");
