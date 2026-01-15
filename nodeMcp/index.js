/**
 * MCP stdio server (ESM)
 * - Ollama 호출 (env 기반)
 * - 한국어(한글)만 출력 강제 + 한자(漢字) 방어(재요청/필터)
 * - ✅ 입력 길이에 따라 bullet 자동 증가(3~12개)
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

/**
 * ENV
 */
const OLLAMA_BASE = process.env.OLLAMA_BASE || "http://127.0.0.1:11434";
const OLLAMA_MODEL = process.env.OLLAMA_MODEL || "qwen2.5:7b";
const DEFAULT_MAX_CHARS = Number(process.env.OLLAMA_MAX_CHARS || 6000);

// 1차 실패 시(한자 섞임 등) 재요청 여부
const RETRY_ON_HAN = (process.env.OLLAMA_RETRY_ON_HAN ?? "true").toLowerCase() !== "false";

// 한자(중국어/일본어 한자 포함) 감지: Script=Han
const HAN_RE = /[\p{Script=Han}]/gu;

/**
 * 모델 출력에 한자(漢字)가 섞였는지 감지
 */
function containsHanChars(s) {
    if (!s) return false;
    return HAN_RE.test(s);
}

/**
 * 최후 방어: 한자 문자만 제거
 */
function stripHanChars(s) {
    if (!s) return s;
    return s.replace(HAN_RE, "");
}

/**
 * ✅ 입력 길이에 따라 bullet 개수 자동 결정
 * - 3 ~ 12개 사이
 * - mode=final이면 조금 더 많이(최대 12)
 */
function decideBulletCount(text, mode = "abstract") {
    const len = (text || "").length;

    // 대략적인 구간
    // < 1,200: 3
    // < 2,500: 4
    // < 5,000: 6
    // < 9,000: 8
    // >= 9,000: 10 (final이면 12까지)
    let n;
    if (len < 1200) n = 3;
    else if (len < 2500) n = 4;
    else if (len < 5000) n = 6;
    else if (len < 9000) n = 8;
    else n = 10;

    if (mode === "final") n += 2; // 더 자세히

    // clamp 3..12
    if (n < 3) n = 3;
    if (n > 12) n = 12;
    return n;
}

/**
 * "한국어(한글)만" + bullet 개수 강제 프롬프트
 */
function buildKoreanOnlyPrompt(text, bulletCount, mode = "abstract") {
    const detailRule =
        mode === "final"
            ? "- 핵심 항목은 가능한 한 구체적으로(근거/조건/수치/예시가 있으면 포함) 작성하라."
            : "- 핵심 항목은 간결하게 요지만 작성하라.";

    return `
너는 문서/웹/텍스트를 요약하는 AI다.

[절대 규칙]
- 반드시 한국어(한글)로만 출력하라.
- 중국어/일본어/한자(漢字) 문자를 출력하지 마라. (한 글자도 금지)
- 영어 단어도 가능하면 한국어로 풀어써라. (약어는 괄호로만 허용)
- 출력에는 요약 결과만 포함하라. (지시문/규칙/프롬프트/과정/메타 설명 금지)
- 입력 원문을 길게 인용하지 마라.
${detailRule}

[출력 형식(반드시 준수)]
- 한 줄 요약: ...
- 핵심(정확히 ${bulletCount}개):
  - ...
  - ...
  (총 ${bulletCount}개가 되도록. 더 늘리거나 줄이면 안 됨)

[입력]
${text}
`.trim();
}

/**
 * 더 강한 "한자 금지" 재요청 프롬프트 (2-pass)
 */
function buildRetryPrompt(originalText, badOutput, bulletCount, mode = "abstract") {
    const detailRule =
        mode === "final"
            ? "- 핵심 항목은 가능한 한 구체적으로 작성하라."
            : "- 핵심 항목은 간결하게 작성하라.";

    return `
너는 "한국어(한글)만" 출력해야 한다.
아래의 이전 출력은 규칙 위반(한자/중국어 포함 가능)이므로 폐기하고, 다시 작성하라.

[절대 규칙]
- 반드시 한국어(한글)로만 출력하라.
- 한자(漢字) 문자를 출력하지 마라. (한 글자도 금지)
- 출력에는 요약 결과만 포함하라. (지시문/과정/메타 설명 금지)
${detailRule}

[출력 형식(반드시 준수)]
- 한 줄 요약: ...
- 핵심(정확히 ${bulletCount}개):
  - ...
  (총 ${bulletCount}개가 되도록)

[이전 출력(폐기 대상)]
${badOutput}

[원문]
${originalText}
`.trim();
}

async function callOllama(prompt, maxChars = DEFAULT_MAX_CHARS) {
    const res = await fetch(`${OLLAMA_BASE}/api/generate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
            model: OLLAMA_MODEL,
            prompt,
            stream: false,
            options: {
                temperature: 0.2,
            },
        }),
    });

    if (!res.ok) {
        const t = await res.text();
        throw new Error(`Ollama error: ${res.status} ${t}`);
    }

    const json = await res.json();
    let out = (json.response || "").trim();

    if (out.length > maxChars) out = out.slice(0, maxChars).trim();
    return out;
}

async function runSummary({ text, maxChars, mode }) {
    if (!text || !text.trim()) {
        return { content: [{ type: "text", text: "" }] };
    }

    const limit = typeof maxChars === "number" ? maxChars : DEFAULT_MAX_CHARS;
    const m = (mode === "final" ? "final" : "abstract");

    // ✅ 길이에 따라 bulletCount 자동 결정
    const bulletCount = decideBulletCount(text, m);

    // 1) 1차 요약
    const p1 = buildKoreanOnlyPrompt(text, bulletCount, m);
    let out = await callOllama(p1, limit);

    // 2) 한자 섞이면 재요청(2-pass)
    if (RETRY_ON_HAN && containsHanChars(out)) {
        const p2 = buildRetryPrompt(text, out, bulletCount, m);
        out = await callOllama(p2, limit);
    }

    // 3) 그래도 섞이면 최후 방어: 한자 제거
    if (containsHanChars(out)) {
        out = stripHanChars(out).trim();
    }

    // 4) 길이 제한 재적용
    if (out.length > limit) out = out.slice(0, limit).trim();

    return { content: [{ type: "text", text: out }] };
}

const server = new McpServer({
    name: "kakaoMcp-node",
    version: "1.0.2",
});

/**
 * main tool
 * - 기존 호출은 text만 줘도 동작
 * - mode는 optional (Spring이 안 보내도 됨)
 */
server.tool(
    "summarize_document",
    "텍스트를 요약합니다. (한국어만 출력, 길이에 따라 핵심 bullet 자동 증가)",
    {
        text: z.string().describe("요약할 텍스트"),
        maxChars: z.number().optional(),
        mode: z.enum(["abstract", "final"]).optional(),
    },
    runSummary
);

/**
 * alias
 */
server.tool(
    "summarize_text",
    "텍스트 요약(alias)",
    {
        text: z.string(),
        maxChars: z.number().optional(),
        mode: z.enum(["abstract", "final"]).optional(),
    },
    runSummary
);

// stdio start
const transport = new StdioServerTransport();
await server.connect(transport);
