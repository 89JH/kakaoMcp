package com.future.kakaomcp.api.doc;

import com.future.kakaomcp.api.doc.dto.SummarizeResponse;
import com.future.kakaomcp.application.doc.DocFacade;
import com.future.kakaomcp.application.doc.command.SummarizeCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/docs")
@RequiredArgsConstructor
public class DocController {

    private final DocFacade docFacade;

    // ✅ 기존 JSON tools list (GET)
    @GetMapping("/tools")
    public Object toolsGet() {
        return docFacade.tools();
    }

    // ✅ POST로 들어와도 동일 응답(헬스체크/오용 대응)
    @PostMapping("/tools")
    public Object toolsPost() {
        return docFacade.tools();
    }

    /**
     * ✅ PlayMCP 스타일: content(TextContent)만 반환
     * - tools/list 결과를 사람이 읽기 좋은 Markdown으로 변환해서 내림
     */
    @GetMapping("/tools/content")
    public Map<String, Object> toolsAsContent() {
        Object toolsObj = docFacade.tools();

        String md = toMarkdownTools(toolsObj);

        Map<String, Object> textContent = new LinkedHashMap<>();
        textContent.put("type", "text");
        textContent.put("text", md);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(textContent));
        return result;
    }

    @PostMapping(value = "/summarize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SummarizeResponse summarize(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", required = false, defaultValue = "abstract") String mode,
            @RequestParam(value = "includeMeta", required = false, defaultValue = "true") Boolean includeMeta
    ) {
        SummarizeCommand command = new SummarizeCommand(mode, includeMeta);
        return docFacade.summarizeFile(file, command);
    }

    @PostMapping(value = "/summarize-text", consumes = MediaType.TEXT_PLAIN_VALUE)
    public SummarizeResponse summarizeText(
            @RequestBody String text,
            @RequestParam(value = "mode", required = false, defaultValue = "abstract") String mode,
            @RequestParam(value = "includeMeta", required = false, defaultValue = "true") Boolean includeMeta
    ) {
        SummarizeCommand command = new SummarizeCommand(mode, includeMeta);
        return docFacade.summarizeText(text, command);
    }

    /**
     * docFacade.tools() 결과(JSON-like Map)를 Markdown으로 변환.
     * toolsObj의 정확한 타입이 무엇이든 안전하게 문자열화하되,
     * "사람이 읽기 쉬운" 형태로 우선 변환 시도.
     */
    @SuppressWarnings("unchecked")
    private String toMarkdownTools(Object toolsObj) {
        // 기대 형태: { "tools": [ { "name": "...", "description": "..."} ] }
        if (toolsObj instanceof Map<?, ?> m) {
            Object tools = m.get("tools");
            if (tools instanceof List<?> list) {
                StringBuilder sb = new StringBuilder();
                sb.append("## Tools\n\n");
                for (Object item : list) {
                    if (item instanceof Map<?, ?> tm) {
                        Object name = tm.get("name");
                        Object desc = tm.get("description");
                        sb.append("- **").append(name == null ? "(unknown)" : name).append("**");
                        if (desc != null) sb.append(": ").append(desc);
                        sb.append("\n");
                    } else {
                        sb.append("- ").append(String.valueOf(item)).append("\n");
                    }
                }
                return sb.toString();
            }
        }

        // fallback
        return "## Tools\n\n" + String.valueOf(toolsObj);
    }
}
