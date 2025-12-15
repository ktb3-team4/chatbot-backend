package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.dto.MessageReactionRequest;
import com.ktb.chatapp.dto.MessageReactionResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReactionHandler {

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    @Qualifier("chatWorkerExecutor") // [추가]
    private final ThreadPoolTaskExecutor chatWorkerExecutor; // [추가]

    @OnEvent(MESSAGE_REACTION)
    public void handleMessageReaction(SocketIOClient client, MessageReactionRequest data) {
        String userId = getUserId(client);
        if (userId == null || userId.isBlank()) {
            client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
            return;
        }

        // 핵심 로직을 비동기 작업자 스레드에 위임하여 Socket.IO 워커 스레드 블로킹 방지
        try {
            chatWorkerExecutor.execute(() -> processMessageReaction(client, data, userId));
        } catch (RejectedExecutionException e) {
            client.sendEvent(ERROR, Map.of("message", "현재 요청이 많아 처리할 수 없습니다."));
        }
    }

    // 블로킹 I/O를 수행하는 실제 비동기 처리 메소드 [추가]
    public void processMessageReaction(SocketIOClient client, MessageReactionRequest data, String userId) {
        try {
            // MongoDB 조회 (블로킹 I/O)
            Message message = messageRepository.findById(data.getMessageId()).orElse(null);
            if (message == null) {
                client.sendEvent(ERROR, Map.of("message", "메시지를 찾을 수 없습니다."));
                return;
            }

            switch (data.getType()) {
                case "add" -> message.addReaction(data.getReaction(), userId);
                case "remove" -> message.removeReaction(data.getReaction(), userId);
                case null, default -> {
                    client.sendEvent(ERROR, Map.of("message", "지원하지 않는 리액션 타입입니다."));
                    return;
                }
            }

            log.debug("Message reaction processed - type: {}, reaction: {}, messageId: {}, userId: {}",
                    data.getType(), data.getReaction(), message.getId(), userId);

            // MongoDB 저장 (블로킹 I/O)
            messageRepository.save(message);

            MessageReactionResponse response = new MessageReactionResponse(
                    message.getId(),
                    message.getReactions()
            );

            // 브로드캐스트
            socketIOServer.getRoomOperations(message.getRoomId())
                    .sendEvent(MESSAGE_REACTION_UPDATE, response);

        } catch (Exception e) {
            log.error("Error handling messageReaction (Async)", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "리액션 처리 중 오류가 발생했습니다."
            ));
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
