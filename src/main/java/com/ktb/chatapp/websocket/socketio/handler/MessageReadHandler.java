// main/java/com/ktb/chatapp/websocket/socketio/handler/MessageReadHandler.java

package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.service.RoomService;
import com.ktb.chatapp.service.UserService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor; // [추가: 비동기 처리]
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 메시지 읽음 상태 처리 핸들러
 * 메시지 읽음 상태 업데이트 및 브로드캐스트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReadHandler {

    private final SocketIOServer socketIOServer;
    private final MessageReadStatusService messageReadStatusService;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository; // 리포지토리는 의존성 그대로 유지 (다른 로직을 위해)
    private final UserRepository userRepository; // 리포지토리는 의존성 그대로 유지
    private final ObjectMapper objectMapper;
    private final UserService userService; // [추가: 캐시된 User 조회]
    private final RoomService roomService; // [추가: 캐시된 Room 조회]

    @Qualifier("chatWorkerExecutor")
    private final ThreadPoolTaskExecutor chatWorkerExecutor; // [추가: 비동기 실행]

    @OnEvent(MARK_MESSAGES_AS_READ)
    public void handleMarkAsRead(SocketIOClient client, MarkAsReadRequest data) {
        String userId = getUserId(client);
        if (userId == null) {
            client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
            return;
        }

        if (data == null || data.getMessageIds() == null || data.getMessageIds().isEmpty()) {
            return;
        }

        // 1. Socket.IO 스레드에서 즉시 비동기 스레드 풀로 작업 위임
        chatWorkerExecutor.execute(() -> processMarkAsRead(client, data, userId));
    }

    public void processMarkAsRead(SocketIOClient client, MarkAsReadRequest data, String userId) {
        try {
            List<String> messageIds = data.getMessageIds().stream().distinct().toList();

            // 2. 메시지에서 채팅방 ID 조회 (MongoDB I/O)
            String roomId = messageRepository.findById(data.getMessageIds().getFirst())
                    .map(Message::getRoomId).orElse(null);

            if (roomId == null || roomId.isBlank()) {
                client.sendEvent(ERROR, Map.of("message", "Invalid room"));
                return;
            }

            // 3. 사용자 정보 조회 (UserService를 통해 Redis Cache 우선 조회) [수정됨]
            User user = userService.findUserById(userId).orElse(null);
            if (user == null) {
                client.sendEvent(ERROR, Map.of("message", "User not found"));
                return;
            }

            // 4. 방 정보 조회 및 권한 확인 (RoomService를 통해 Redis Cache 우선 조회) [수정됨]
            Room room = roomService.findRoomById(roomId).orElse(null);
            if (room == null || !room.getParticipantIds().contains(userId)) {
                client.sendEvent(ERROR, Map.of("message", "Room access denied"));
                return;
            }

            // 5. 메시지 목록 조회 (MongoDB I/O)
            var messages = messageRepository.findAllById(messageIds);
            Set<String> senderIds = messages.stream()
                    .map(Message::getSenderId)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toSet());

            if (senderIds.isEmpty()) {
                client.sendEvent(ERROR, Map.of("message", "No message senders found"));
                return;
            }

            // 6. 읽음 상태 업데이트 (MessageReadStatusService - Redis Batch)
            messageReadStatusService.updateReadStatus(messageIds, userId);

            MessagesReadResponse response = new MessagesReadResponse(userId, messageIds);

            // 7. 브로드캐스트
            senderIds.forEach(senderId ->
                    socketIOServer.getRoomOperations("user:" + senderId)
                            .sendEvent(MESSAGES_READ, response)
            );

        } catch (Exception e) {
            log.error("Error handling markMessagesAsRead (Async)", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "읽음 상태 업데이트 중 오류가 발생했습니다."
            ));
        }
    }

    private String getUserId(SocketIOClient client) {
        Object value = client.get("user");
        if (value == null) {
            return null;
        }
        // 메모리 내 객체인 경우 (같은 서버 내)
        if (value instanceof SocketUser socketUser) {
            return socketUser.id();
        }
        // Redis에서 역직렬화된 Map인 경우 (다중 서버 환경)
        try {
            return objectMapper.convertValue(value, SocketUser.class).id();
        } catch (Exception e) {
            log.warn("Failed to convert session user data to SocketUser: {}", e.getMessage());
            return null;
        }
    }
}