/**
 * MCP stdio server (ESM)
 * - Ollama 호출 (env 기반)
 * - 한국어(한글)만 출력 강제 + 한자(漢字) 방어(재요청/필터)
 * - ✅ 입력 길이에 따라 bullet 자동 증가(5~20개)
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

// ✅ Ollama fetch 타임아웃(ms) (기본 10분)
const OLLAMA_TIMEOUT_MS = Number(process.env.OLLAMA_TIMEOUT_MS || 600000);

// ✅ "제안/추천/개선" 섹션 금지 규칙은 옵션으로만(기본 OFF)
const FORBID_EXTRA_SECTIONS =
    (process.env.MCP_FORBID_EXTRA_SECTIONS ?? "false").toLowerCase() === "true";

// 1차 실패 시(한자 섞임 등) 재요청 여부
const RETRY_ON_HAN =
    (process.env.OLLAMA_RETRY_ON_HAN ?? "true").toLowerCase() !== "false";

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
 * - 5 ~ 20개 사이
 * - mode=final이면 조금 더 많이(최대 20)
 */
function decideBulletCount(text, mode = "abstract") {
    const len = (text || "").length;

    // ✅ 더 민감하게: 조금만 길어도 6~10개로 올라가게
    let n;
    if (len < 700) n = 5;
    else if (len < 1200) n = 6;
    else if (len < 2000) n = 8;
    else if (len < 3500) n = 10;
    else if (len < 6000) n = 12;
    else if (len < 9000) n = 15;
    else if (len < 14000) n = 18;
    else n = 20;

    if (mode === "final") n += 2;

    // clamp 5..20
    if (n < 5) n = 5;
    if (n > 20) n = 20;
    return n;
}

/**
 * ✅ Java 프롬프트에서 "정확히 N개"가 들어오면 그 값을 우선 사용
 * 예) "핵심(정확히 12개):" 또는 "정확히 12개" 등
 */
function extractBulletCountFromPrompt(prompt) {
    if (!prompt) return null;
    const s = String(prompt);
    const m = s.match(/정확히\s*(\d{1,2})\s*개/);
    if (!m) return null;
    const n = Number(m[1]);
    if (!Number.isFinite(n)) return null;
    return Math.max(5, Math.min(20, n));
}

/**
 * "핵심" 섹션 안의 bullet 개수만 카운트
 * - 개선: "- " 형태도 카운트
 */
function countCoreBullets(output) {
    const lines = String(output || "").split(/\r?\n/);

    let inCore = false;
    let count = 0;

    for (const raw of lines) {
        const line = raw.trimEnd();

        // "핵심(" 또는 "- 핵심:" 등을 만나면 core 섹션 시작
        if (/^\s*-\s*핵심/.test(line)) {
            inCore = true;
            continue;
        }

        if (!inCore) continue;

        // core 섹션에서 빈 줄 만나면 종료(다른 섹션 방지)
        if (/^\s*$/.test(line)) break;

        // "- ..." 형태를 bullet로 카운트 (들여쓰기 무관)
        if (/^\s*-\s+/.test(line)) count++;
    }

    return count;
}

function hasForbiddenSection(output) {
    // "제안/추천/개선/결론/추가" 같은 섹션을 자동으로 붙이는 것을 방지
    // (앞에 '-'가 있거나 없거나 모두 방지)
    return /(^|\n)\s*-?\s*(제안|추천|개선|결론|추가)\s*:?/m.test(output || "");
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
${
        FORBID_EXTRA_SECTIONS
            ? "- 아래 2개 섹션(한 줄 요약, 핵심) 외의 섹션을 절대 추가하지 마라. (예: 제안/추천/개선/결론/추가정보 금지)"
            : ""
    }
- 입력 원문을 길게 인용하지 마라.
${detailRule}

[출력 형식(반드시 준수)]
- 한 줄 요약: (1문장)
- 핵심(정확히 ${bulletCount}개):
- ...
- ...
(총 ${bulletCount}개가 되도록. 더 늘리거나 줄이면 안 됨)

[입력]
${text}
`.trim();
}

/**
 * 더 강한 재요청 프롬프트 (2-pass)
 */
function buildRetryPrompt(originalText, badOutput, bulletCount, mode = "abstract") {
    const detailRule =
        mode === "final"
            ? "- 핵심 항목은 가능한 한 구체적으로 작성하라."
            : "- 핵심 항목은 간결하게 작성하라.";

    return `
너는 "한국어(한글)만" 출력해야 한다.
아래의 이전 출력은 규칙 위반(한자 포함/섹션 추가/핵심 bullet 개수 불일치 가능)이므로 폐기하고, 다시 작성하라.

[절대 규칙]
- 반드시 한국어(한글)로만 출력하라.
- 한자(漢字) 문자를 출력하지 마라. (한 글자도 금지)
- 출력에는 요약 결과만 포함하라. (지시문/과정/메타 설명 금지)
${
        FORBID_EXTRA_SECTIONS
            ? "- 아래 2개 섹션(한 줄 요약, 핵심) 외의 섹션을 절대 추가하지 마라. (예: 제안/추천/개선/결론/추가정보 금지)"
            : ""
    }
- 핵심 bullet은 반드시 정확히 지정된 개수여야 한다. (최소 5, 최대 20 범위)
${detailRule}

[출력 형식(반드시 준수)]
- 한 줄 요약: (1문장)
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
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), OLLAMA_TIMEOUT_MS);

    const res = await fetch(`${OLLAMA_BASE}/api/generate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        signal: controller.signal,
        body: JSON.stringify({
            model: OLLAMA_MODEL,
            prompt,
            stream: false,
            options: { temperature: 0.2 },
        }),
    }).finally(() => clearTimeout(timer));

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
    const m = mode === "final" ? "final" : "abstract";

    // ✅ 1) 우선 입력 길이로 기본 bulletCount 산정
    const baseCount = decideBulletCount(text, m);

    // ✅ 2) 프롬프트를 먼저 만들고,
    //    Java 쪽에서 "정확히 N개"를 강제해 넣은 경우 그 값을 최우선으로 적용
    const p1 = buildKoreanOnlyPrompt(text, baseCount, m);
    const forced = extractBulletCountFromPrompt(p1);
    const bulletCount = forced ?? baseCount;

    // 1) 1차 요약
    const pFinal = forced ? buildKoreanOnlyPrompt(text, bulletCount, m) : p1;
    let out = await callOllama(pFinal, limit);

    // 2) 규칙 위반이면 재요청(최대 2회)
    let tries = 0;
    while (
        tries < 2 &&
        ((RETRY_ON_HAN && containsHanChars(out)) ||
            (FORBID_EXTRA_SECTIONS && hasForbiddenSection(out)) ||
            countCoreBullets(out) !== bulletCount)
        ) {
        const p2 = buildRetryPrompt(text, out, bulletCount, m);
        out = await callOllama(p2, limit);
        tries++;
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
    version: "1.0.4",
});

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
