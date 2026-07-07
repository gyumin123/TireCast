package com.tirecast.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * F-DSH-02 보조: 이미지 파일 저장.
 * DB에는 BLOB이 아닌 경로 문자열만 저장한다 (ERD 설계 참고사항).
 */
@Service
public class StorageService {

    private final Path uploadDir;

    public StorageService(@Value("${tirecast.upload-dir}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir);
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 디렉토리를 생성할 수 없습니다: " + uploadDir, e);
        }
    }

    /** 이미지를 저장하고 웹에서 접근 가능한 경로(/uploads/...)를 반환 */
    public String save(byte[] bytes, String prefix) {
        String filename = prefix + "_" + UUID.randomUUID() + ".jpg";
        try {
            Files.write(uploadDir.resolve(filename), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("이미지 저장에 실패했습니다.", e);
        }
        return "/uploads/" + filename;
    }
}
