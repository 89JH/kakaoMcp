package com.future.kakaomcp.api.doc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // ✅ null 필드는 JSON에서 제거
public class SummarizeResponse {

    private String tool;
    private String mode;
    private String inputFileName;
    private String summaryText;

    // ✅ includeMeta=false면 null로 내려서 JSON에서 아예 사라지게
    private Map<String, Object> meta;
}
