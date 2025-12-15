package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.StandardResponse;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

@Tag(name = "파일 (Files)", description = "파일 관리 API (삭제 기능)")
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;
    private final UserRepository userRepository;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestBody Map<String, Object> payload) {

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "File upload processed successfully (Test Dummy)");

        response.put("file", payload);
        response.put("mimetype", "application/octet-stream");

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "파일 삭제", description = "업로드된 파일을 삭제합니다. 본인이 업로드한 파일만 삭제 가능합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "파일 삭제 성공",
                    content = @Content(schema = @Schema(implementation = StandardResponse.class))),
            @ApiResponse(responseCode = "403", description = "권한 없음",
                    content = @Content(schema = @Schema(implementation = StandardResponse.class))),
            @ApiResponse(responseCode = "404", description = "파일을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = StandardResponse.class))),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류",
                    content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFile(@PathVariable String id, Principal principal) {
        try {
            User user = userRepository.findByEmail(principal.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + principal.getName()));

            boolean deleted = fileService.deleteFile(id, user.getId());

            if (deleted) {
                return ResponseEntity.ok(StandardResponse.success("파일이 삭제되었습니다."));
            } else {
                return ResponseEntity.status(400).body(StandardResponse.error("파일 삭제에 실패했습니다."));
            }

        } catch (RuntimeException e) {
            log.error("파일 삭제 중 에러 발생: {}", id, e);
            String errorMessage = e.getMessage();

            if (errorMessage != null && errorMessage.contains("찾을 수 없습니다")) {
                return ResponseEntity.status(404).body(StandardResponse.error("파일을 찾을 수 없습니다."));
            } else if (errorMessage != null && errorMessage.contains("권한")) {
                return ResponseEntity.status(403).body(StandardResponse.error("파일을 삭제할 권한이 없습니다."));
            }

            return ResponseEntity.status(500).body(StandardResponse.error("파일 삭제 중 오류가 발생했습니다."));
        }
    }
}