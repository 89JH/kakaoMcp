package com.future.kakaomcp.domain.file;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;

import com.future.kakaomcp.common.exception.BizException;
import com.future.kakaomcp.domain.file.model.TempStoredFile;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TempFileService {

    private final Path baseDir;

    public TempFileService(@Value("${app.temp-dir}") String tempDir) {
        this.baseDir = Paths.get(tempDir).normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new BizException("TEMP_DIR_CREATE_FAILED", "임시 디렉토리 생성 실패: " + baseDir, e);
        }
    }

    public TempStoredFile save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("FILE_EMPTY", "업로드 파일이 비어있습니다.");
        }

        String original = file.getOriginalFilename();
        String safeName = UUID.randomUUID() + "-" + (original == null ? "upload.bin" : original.replaceAll("[\\\\/]", "_"));
        Path target = baseDir.resolve(safeName).normalize();

        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException("FILE_SAVE_FAILED", "임시 파일 저장 실패: " + safeName, e);
        }

        return TempStoredFile.builder()
                .originalFileName(original == null ? safeName : original)
                .path(target)
                .createdAt(Instant.now())
                .build();
    }

    public void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}