package com.future.kakaomcp.common.exception;

import com.future.kakaomcp.domain.doc.exception.DrmProtectedEpubException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorResponse> handleBiz(BizException e) {
        return ResponseEntity.badRequest().body(
                ErrorResponse.builder()
                        .code(e.getCode())
                        .message(e.getMessage())
                        .build()
        );
    }

    /**
     * ✅ DRM 보호 EPUB: 본문 추출/요약 불가
     * - HTTP 422
     * - OPF에서 뽑은 메타를 meta.tikaMetadata로 반환
     */
    @ExceptionHandler(DrmProtectedEpubException.class)
    public ResponseEntity<ErrorResponse> handleDrm(DrmProtectedEpubException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                ErrorResponse.builder()
                        .code("DRM_PROTECTED_EPUB")
                        .message(e.getMessage())
                        .meta(Map.of(
                                "tikaMetadata", e.getExtractedMetadata(),
                                "hint", "DRM 보호(Adobe ADE) EPUB은 본문 추출/요약이 불가합니다. DRM 없는 EPUB/PDF로 다시 제공되는 파일만 업로드해주세요."
                        ))
                        .build()
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUpload(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                ErrorResponse.builder()
                        .code("FILE_TOO_LARGE")
                        .message("업로드 파일 용량이 제한을 초과했습니다.")
                        .build()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception e) {
        return ResponseEntity.internalServerError().body(
                ErrorResponse.builder()
                        .code("INTERNAL_ERROR")
                        .message(e.getMessage())
                        .build()
        );
    }
}
