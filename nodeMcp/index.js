/**
 * MCP stdio server (ESM)
 * - package.json: "type":"module" 환경 대응
 * - Spring이 stdio로 node를 띄우는 구조에서 사용
 *
 * tools:
 * - summarize_document (Spring 내부 호출용, 필수)
 * - summarize_text (alias, 선택)
 */

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const server = new McpServer({
    name: "kakaoMcp-node",
    version: "0.0.3",
});

// ✅ 요약 엔진(더미). 여기만 실제 LLM 호출로 바꾸면 됨.
function summarizeToMarkdown({ text, mode = "abstract", maxChars = 6000 }) {
    const src = (text || "").trim();
    if (!src) return "# 오류\n입력 텍스트가 비어 있습니다.";

    const clean = src.replace(/\s+/g, " ").trim();
    const preview = clean.slice(0, Math.min(clean.length, 1200));

    const md =
        `# 요약\n` +
        `## 모드\n- ${mode}\n\n` +
        `## 핵심\n` +
        `- ${preview.slice(0, 200)}${preview.length > 200 ? "..." : ""}\n` +
        (preview.length > 260
            ? `- ${preview.slice(200, 420)}${preview.length > 420 ? "..." : ""}\n`
            : "") +
        `\n## 참고\n- 입력 길이: ${clean.length} chars\n`;

    return md.length > maxChars ? md.slice(0, maxChars) + "\n\n...(truncated)" : md;
}

async function runSummary({ text, mode, maxChars }) {
    const md = summarizeToMarkdown({
        text,
        mode: mode ?? "abstract",
        maxChars: maxChars ?? 6000,
    });
    return { content: [{ type: "text", text: md }] };
}

/**
 * ✅ 필수: Spring이 호출하는 이름
 * summarize_document(text, mode?, maxChars?)
 */
server.tool(
    "summarize_document",
    "텍스트를 Markdown 형식으로 요약합니다. (Spring 내부 요약 엔진 호출용)",
    {
        text: z.string().describe("요약할 원문 텍스트"),
        mode: z.string().optional().describe("요약 모드(예: abstract/final)"),
        maxChars: z.number().int().optional().describe("요약 최대 글자 수"),
    },
    runSummary
);

/**
 * ✅ 선택: summarize_text alias (text만)
 * - 추후 Spring이 이 이름으로 직접 호출하게 바꿀 수도 있음
 */
server.tool(
    "summarize_text",
    "긴 텍스트를 Markdown 형식으로 요약합니다.",
    {
        text: z.string().describe("요약할 원문 텍스트"),
    },
    async ({ text }) => runSummary({ text, mode: "abstract", maxChars: 6000 })
);

// stdio 실행
const transport = new StdioServerTransport();
await server.connect(transport);
