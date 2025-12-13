package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.fasterxml.jackson.databind.ObjectMapper; // [추가]
import com.ktb.chatapp.dto.MessageReactionRequest;
import com.ktb.chatapp.dto.MessageReactionResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 메시지 리액션 처리 핸들러
 * 메시지 이모지 리액션 추가/제거 및 브로드캐스트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReactionHandler {

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper; // [추가] ObjectMapper 주입

    @OnEvent(MESSAGE_REACTION)
    public void handleMessageReaction(SocketIOClient client, MessageReactionRequest data) {
        try {
            String userId = getUserId(client);
            if (userId == null || userId.isBlank()) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

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

            messageRepository.save(message);

            MessageReactionResponse response = new MessageReactionResponse(
                    message.getId(),
                    message.getReactions()
            );

            socketIOServer.getRoomOperations(message.getRoomId())
                    .sendEvent(MESSAGE_REACTION_UPDATE, response);

        } catch (Exception e) {
            log.error("Error handling messageReaction", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "리액션 처리 중 오류가 발생했습니다."
            ));
        }
    }

    // [수정] Redis 등에서 가져온 데이터(LinkedHashMap)를 SocketUser로 안전하게 변환
    private String getUserId(SocketIOClient client) {
        Object value = client.get("user");
        if (value == null) {
            return null;
        }
        // 메모리 내 객체인 경우
        if (value instanceof SocketUser socketUser) {
            return socketUser.id();
        }
        // Redis에서 역직렬화된 Map인 경우
        try {
            return objectMapper.convertValue(value, SocketUser.class).id();
        } catch (Exception e) {
            log.warn("Failed to convert session user data to SocketUser: {}", e.getMessage());
            return null;
        }
    }
}