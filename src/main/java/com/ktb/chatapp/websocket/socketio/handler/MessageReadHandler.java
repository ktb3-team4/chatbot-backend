package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.fasterxml.jackson.databind.ObjectMapper; // [추가]
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper; // [추가] ObjectMapper 주입

    @OnEvent(MARK_MESSAGES_AS_READ)
    public void handleMarkAsRead(SocketIOClient client, MarkAsReadRequest data) {
        try {
            String userId = getUserId(client);
            if (userId == null) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            if (data == null || data.getMessageIds() == null || data.getMessageIds().isEmpty()) {
                return;
            }

            List<String> messageIds = data.getMessageIds().stream().distinct().toList();

            // 첫 번째 메시지로 채팅방 ID 조회 (모든 메시지가 같은 방이라고 가정)
            String roomId = messageRepository.findById(data.getMessageIds().getFirst())
                    .map(Message::getRoomId).orElse(null);

            if (roomId == null || roomId.isBlank()) {
                client.sendEvent(ERROR, Map.of("message", "Invalid room"));
                return;
            }

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                client.sendEvent(ERROR, Map.of("message", "User not found"));
                return;
            }

            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null || !room.getParticipantIds().contains(userId)) {
                client.sendEvent(ERROR, Map.of("message", "Room access denied"));
                return;
            }

            var messages = messageRepository.findAllById(messageIds);
            Set<String> senderIds = messages.stream()
                    .map(Message::getSenderId)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toSet());

            if (senderIds.isEmpty()) {
                client.sendEvent(ERROR, Map.of("message", "No message senders found"));
                return;
            }

            messageReadStatusService.updateReadStatus(messageIds, userId);

            MessagesReadResponse response = new MessagesReadResponse(userId, messageIds);

            // Broadcast only to original senders to avoid room-wide ACK storms
            senderIds.forEach(senderId ->
                    socketIOServer.getRoomOperations("user:" + senderId)
                            .sendEvent(MESSAGES_READ, response)
            );

        } catch (Exception e) {
            log.error("Error handling markMessagesAsRead", e);
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
