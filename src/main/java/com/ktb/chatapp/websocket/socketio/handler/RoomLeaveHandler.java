package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.exception.RoomNotFoundException;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.RoomService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 방 퇴장 처리 핸들러
 * 채팅방 퇴장, 스트리밍 세션 종료, 참가자 목록 업데이트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomLeaveHandler {

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final UserRooms userRooms;
    private final MessageResponseMapper messageResponseMapper;
    private final ObjectMapper objectMapper;
    private final RoomService roomService;

    @Qualifier("chatWorkerExecutor")
    private final ThreadPoolTaskExecutor chatWorkerExecutor;

    @OnEvent(LEAVE_ROOM)
    public void handleLeaveRoom(SocketIOClient client, String roomId) {
        String userId = getUserId(client);

        if (userId == null) {
            return;
        }

        if (roomId == null || roomId.isBlank()) {
            return;
        }

        if (!userRooms.isInRoom(userId, roomId)) {
            log.debug("User {} is not in room {}", userId, roomId);
            return;
        }

        // 핵심 로직을 비동기 작업자 스레드에 위임 (논블로킹)
        try {
            chatWorkerExecutor.execute(() -> processLeaveRoom(client, roomId, userId));
        } catch (RejectedExecutionException e) {
            log.warn("leaveRoom rejected due to worker saturation - roomId: {}, userId: {}", roomId, userId);
        }
    }

    // 블로킹 I/O 작업을 수행하는 비동기 메서드
    public void processLeaveRoom(SocketIOClient client, String roomId, String userId) {
        String userName = getUserName(client);

        try {
            // 다른 스레드에서 이미 처리한 경우 중복 메시지 전송을 막기 위해 한 번 더 확인
            if (!userRooms.isInRoom(userId, roomId)) {
                log.debug("Skip leaveRoom - already processed for user {} room {}", userId, roomId);
                return;
            }

            Room room;
            try {
                room = roomService.findRoomById(roomId);
            } catch (IllegalArgumentException | RoomNotFoundException e) {
                log.warn("Room {} not found or invalid id for user {}", roomId, userId);
                return;
            }
            User user = userRepository.findById(userId).orElse(null);

            if (user == null) {
                log.warn("User {} has no access to room {}", userId, roomId);
                return;
            }

            // Presence cleanup only (DB 참가자 목록은 유지)
            client.leaveRoom(roomId);
            userRooms.remove(userId, roomId);

            log.info("User {} left room {}", userName, room.getName());

            log.debug("Leave room cleanup - roomId: {}, userId: {}", roomId, userId);

            sendSystemMessage(roomId, userName + "님이 퇴장하였습니다.");
            broadcastParticipantList(roomId);
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(USER_LEFT, createUserLeftPayload(userId, userName));

        } catch (Exception e) {
            log.error("Error processing leaveRoom", e);
        }
    }

    private Map<String, String> createUserLeftPayload(String userId, String userName) {
        Map<String, String> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("userName", userName);
        return payload;
    }


    private void sendSystemMessage(String roomId, String content) {
        try {
            Message systemMessage = new Message();
            systemMessage.setRoomId(roomId);
            systemMessage.setContent(content);
            systemMessage.setType(MessageType.system);
            systemMessage.setTimestamp(LocalDateTime.now());
            systemMessage.setMentions(new ArrayList<>());
            systemMessage.setIsDeleted(false);
            systemMessage.setReactions(new HashMap<>());
            systemMessage.setReaders(new ArrayList<>());
            systemMessage.setMetadata(new HashMap<>());

            // 시스템 메시지는 sender 정보가 없으므로 임베딩 필드는 null로 둡니다.

            Message savedMessage = messageRepository.save(systemMessage);

            // [수정] 매퍼 시그니처 변경 반영 (User 인자 제거)
            MessageResponse response = messageResponseMapper.mapToMessageResponse(savedMessage);

            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGE, response);

        } catch (Exception e) {
            log.error("Error sending system message", e);
        }
    }

    private void broadcastParticipantList(String roomId) {
        Room room;
        try {
            room = roomService.findRoomById(roomId);
        } catch (IllegalArgumentException | RoomNotFoundException e) {
            return;
        }

        Set<String> participantIds = room.getParticipantIds();
        if (participantIds == null || participantIds.isEmpty()) {
            return;
        }

        // [최적화] findAllById를 사용하여 N+1 쿼리 문제 해결
        List<UserResponse> participantList = userRepository.findAllById(participantIds)
                .stream()
                .map(UserResponse::from)
                .toList();

        if (participantList.isEmpty()) {
            return;
        }

        socketIOServer.getRoomOperations(roomId)
                .sendEvent(PARTICIPANTS_UPDATE, participantList);
    }

    private SocketUser getUserDto(SocketIOClient client) {
        Object value = client.get("user");
        if (value == null) {
            return null;
        }
        if (value instanceof SocketUser socketUser) {
            return socketUser;
        }
        try {
            return objectMapper.convertValue(value, SocketUser.class);
        } catch (Exception e) {
            log.warn("Failed to convert session user data to SocketUser: {}", e.getMessage());
            return null;
        }
    }

    private String getUserId(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.id() : null;
    }

    private String getUserName(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.name() : null;
    }
}
