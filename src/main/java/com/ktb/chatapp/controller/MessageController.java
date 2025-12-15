package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.StandardResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.service.UserService;
import com.ktb.chatapp.websocket.socketio.handler.MessageLoader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 메시지 시스템 REST API 컨트롤러
 *
 * - GET /api/message/rooms/:roomId/messages → 첫 페이지 REST 반환 (SSR/초기 진입용)
 * - 추가 페이지 및 실시간은 Socket.IO로 처리
 */
@Tag(name = "메시지 (Messages)", description = "메시지 관련 API (주의: 실제 메시지 기능은 Socket.IO를 통해 제공됩니다)")
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/message")
public class MessageController {

    private final MessageLoader messageLoader;
    private final RoomRepository roomRepository;
    private final UserService userService;

    /**
     * 채팅방 메시지 첫 페이지 조회 (SSR/새로고침 대응)
     * 나머지 페이지는 Socket.IO 'fetchPreviousMessages' 이벤트 사용
     */
    @Operation(
        summary = "메시지 조회 (첫 페이지)",
        description = "채팅방 메시지 첫 페이지를 반환합니다. 추가 페이지는 Socket.IO의 'fetchPreviousMessages' 이벤트를 사용하세요.",
        deprecated = false
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "메시지 조회 성공",
            content = @Content(schema = @Schema(implementation = FetchMessagesResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "403", description = "방 접근 권한 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"message\":\"채팅방 접근 권한이 없습니다.\"}"))),
        @ApiResponse(responseCode = "404", description = "방을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<?> loadMessages(
            @Parameter(description = "채팅방 ID") @PathVariable String roomId,
            @Parameter(description = "이전 메시지 타임스탬프(ms) - 페이지네이션") @RequestParam(required = false) Long before,
            @Parameter(description = "조회할 메시지 개수", example = "30") @RequestParam(defaultValue = "30") Integer limit,
            Principal principal) {
        try {
            if (principal == null || principal.getName() == null) {
                return ResponseEntity.status(401).body(StandardResponse.error("인증이 필요합니다."));
            }

            User user = userService.findUserByEmail(principal.getName()).orElse(null);
            if (user == null) {
                return ResponseEntity.status(401).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
            }

            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                return ResponseEntity.status(404).body(StandardResponse.error("채팅방을 찾을 수 없습니다."));
            }
            if (!room.getParticipantIds().contains(user.getId())) {
                return ResponseEntity.status(403).body(StandardResponse.error("채팅방 접근 권한이 없습니다."));
            }

            int safeLimit = (limit != null && limit > 0 && limit <= 100) ? limit : 30;
            long beforeTs = before != null ? before : 0L;
            FetchMessagesRequest request = new FetchMessagesRequest(roomId, safeLimit, beforeTs);
            FetchMessagesResponse response = messageLoader.loadMessages(request, user.getId());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Message REST API error for room {}", roomId, e);
            return ResponseEntity.status(500).body(
                    StandardResponse.error("메시지를 불러오는 중 오류가 발생했습니다.")
            );
        }
    }
}
