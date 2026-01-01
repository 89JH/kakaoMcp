package com.future.kakaomcp.api.doc;

import com.future.kakaomcp.api.doc.dto.SummarizeResponse;
import com.future.kakaomcp.application.doc.DocFacade;
import com.future.kakaomcp.application.doc.command.SummarizeCommand;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/docs")
@RequiredArgsConstructor
public class DocController {

    private final DocFacade docFacade;

    @GetMapping("/tools")
    public Object tools() {
        return docFacade.tools();
    }

    @PostMapping(value = "/summarize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SummarizeResponse summarize(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "mode", defaultValue = "abstract") @NotBlank String mode
    ) {
        return docFacade.summarize(file, SummarizeCommand.builder().mode(mode).build());
    }
}