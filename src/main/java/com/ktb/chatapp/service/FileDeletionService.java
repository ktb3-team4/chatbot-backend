package com.ktb.chatapp.service;

import com.ktb.chatapp.util.FileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileDeletionService { // ⬅️ 새로 생성

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    /**
     * 지정된 파일을 비동기로 삭제합니다.
     * @param filename 삭제할 파일 이름
     */
    @Async("chatWorkerExecutor") // ⬅️ 비동기 처리 적용 및 실행기 지정
    public void deleteProfileImageAsync(String filename) {
        if (filename == null || filename.isEmpty()) {
            return;
        }

        try {
            // 경로 검증은 이미 파일 업로드 시에 수행되었으므로 간단히 처리
            Path filePath = Paths.get(uploadDir, filename).toAbsolutePath().normalize();

            // 보안 검증 (업로드 디렉토리 벗어나는지)
            FileUtil.validatePath(filePath, Paths.get(uploadDir).toAbsolutePath().normalize());

            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("비동기 파일 삭제 성공: {}", filename);
            } else {
                log.warn("비동기 파일 삭제 실패: 파일이 존재하지 않음 - {}", filename);
            }
        } catch (IOException e) {
            log.error("비동기 파일 삭제 중 I/O 오류 발생: {} - {}", filename, e.getMessage());
            // 파일 삭제 실패는 사용자 응답에 영향을 주지 않으므로 여기서 예외를 밖으로 던지지 않습니다.
        } catch (Exception e) {
            log.error("비동기 파일 삭제 중 오류 발생: {} - {}", filename, e.getMessage());
        }
    }
}