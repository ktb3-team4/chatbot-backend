package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MARK_MESSAGES_AS_READ;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGES_READ;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReadHandler {

    private final SocketIOServer socketIOServer;
    private final MessageReadStatusService messageReadStatusService;
    private final ObjectMapper objectMapper;

    @Value("${chatapp.read-status.max-ids-per-request:200}")
    private int maxIdsPerRequest;

    @Qualifier("chatWorkerExecutor")
    private final ThreadPoolTaskExecutor chatWorkerExecutor;

    @OnEvent(MARK_MESSAGES_AS_READ)
    public void handleMarkAsRead(SocketIOClient client, MarkAsReadRequest data) {
        String userId = getUserId(client);
        if (userId == null) {
            return;
        }

        if (data == null || data.getMessageIds() == null || data.getMessageIds().isEmpty()
                || data.getRoomId() == null || data.getRoomId().isBlank()) {
            return;
        }

        try {
            chatWorkerExecutor.execute(() -> processMarkAsRead(data, userId));
        } catch (RejectedExecutionException e) {
            log.warn("MarkAsRead rejected due to worker saturation for user {}", userId);
        }
    }

    private void processMarkAsRead(MarkAsReadRequest data, String userId) {
        try {
            List<String> filteredIds = data.getMessageIds().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .toList();

            int cap = Math.max(1, maxIdsPerRequest);
            List<String> messageIds = filteredIds.size() > cap
                    ? filteredIds.subList(0, cap)
                    : filteredIds;

            if (filteredIds.size() > cap) {
                log.warn("MarkAsRead truncated: requested={}, capped={} for user={} room={}",
                        filteredIds.size(), cap, userId, data.getRoomId());
            }

            if (messageIds.isEmpty()) {
                return;
            }

            String roomId = data.getRoomId();

            messageReadStatusService.bufferRead(messageIds, userId, roomId);

            MessagesReadResponse response = new MessagesReadResponse(userId, messageIds);
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGES_READ, response);

        } catch (Exception e) {
            log.debug("MarkAsRead ignored due to error", e);
        }
    }

    private String getUserId(SocketIOClient client) {
        Object value = client.get("user");
        if (value == null) {
            return null;
        }
        if (value instanceof SocketUser socketUser) {
            return socketUser.id();
        }
        try {
            return objectMapper.convertValue(value, SocketUser.class).id();
        } catch (Exception e) {
            log.warn("Failed to convert session user data to SocketUser: {}", e.getMessage());
            return null;
        }
    }
}
