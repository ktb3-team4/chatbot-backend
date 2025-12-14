package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 메시지 조회 처리 핸들러
 * 이전 메시지 조회, 재시도 로직, 읽음 상태 업데이트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageFetchHandler {

    private final RoomRepository roomRepository;
    private final MessageLoader messageLoader;
    private final ObjectMapper objectMapper;

    @Qualifier("chatWorkerExecutor")
    private final ThreadPoolTaskExecutor chatWorkerExecutor;

    @OnEvent(FETCH_PREVIOUS_MESSAGES)
    public void handleFetchMessages(SocketIOClient client, FetchMessagesRequest data) {
        String userId = getUserId(client);
        if (userId == null) {
            client.sendEvent(ERROR, Map.of("code", "UNAUTHORIZED", "message", "인증이 필요합니다."));
            return;
        }

        chatWorkerExecutor.execute(() -> processFetchMessages(client, data, userId));
    }
    private void processFetchMessages(SocketIOClient client, FetchMessagesRequest data, String userId) {
        final String roomId = data.roomId();

        try {
            // 권한 체크
            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null || !room.getParticipantIds().contains(userId)) {
                client.sendEvent(ERROR, Map.of(
                        "code", "LOAD_ERROR",
                        "message", "채팅방 접근 권한이 없습니다."
                ));
                return;
            }

            client.sendEvent(MESSAGE_LOAD_START);

            // 메시지 로드 (블로킹 작업)
            FetchMessagesResponse result = messageLoader.loadMessages(data, userId);

            client.sendEvent(PREVIOUS_MESSAGES_LOADED, result);

        } catch (Exception e) {
            log.error("Error processing fetchPreviousMessages for room {}", roomId, e);
            client.sendEvent(ERROR, Map.of(
                    "code", "LOAD_ERROR",
                    "message", e.getMessage() != null ?
                            e.getMessage() : "이전 메시지를 불러오는 중 오류가 발생했습니다."
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
