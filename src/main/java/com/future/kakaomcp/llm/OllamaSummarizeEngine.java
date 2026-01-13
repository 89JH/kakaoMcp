package com.future.kakaomcp.llm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OllamaSummarizeEngine implements SummarizeEngine {

    private final OllamaClient ollamaClient;

    @Override
    public String summarize(String text, String mode, int maxChars) {
        String safeMode = (mode == null || mode.isBlank()) ? "abstract" : mode.trim();
        String safeText = text == null ? "" : text;

        String prompt = buildPrompt(safeText, safeMode, maxChars);
        return ollamaClient.generate(prompt, maxChars);
    }

    private String buildPrompt(String text, String mode, int maxChars) {
        // ✅ 포맷 고정(Markdown)은 Spring 컨트롤러/포맷터에서 이미 처리 중이라
        //    여기서는 "요약 텍스트"만 만들도록 유도
        String modeGuide = switch (mode) {
            case "final" -> "문서 전체를 통합한 최종 요약";
            case "keypoints" -> "핵심 포인트 위주의 요약";
            default -> "짧은 초록(abstract) 요약";
        };

        return """
너는 한국어로 정확하고 간결하게 요약하는 어시스턴트다.
출력은 '요약 텍스트만' 작성하고, 제목/헤더/마크다운 섹션/불필요한 메타(doi, 이메일, 소속, url)는 쓰지 마라.
요약 길이는 대략 %d자 이내를 목표로 한다.

[요약 모드]
%s

[원문]
%s
""".formatted(maxChars, modeGuide, text);
    }
}
