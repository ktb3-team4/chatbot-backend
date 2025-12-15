package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * Socket.IO Chat Handler
 * 어노테이션 기반 이벤트 처리와 인증 흐름을 정의한다.
 * 연결/해제 및 중복 로그인 처리를 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class ConnectionLoginHandler {

    private final SocketIOServer socketIOServer;
    private final ConnectedUsers connectedUsers;
    private final UserRooms userRooms;
    private final RoomJoinHandler roomJoinHandler;
    private final RoomLeaveHandler roomLeaveHandler;
    private final ObjectMapper objectMapper;
    @Qualifier("chatWorkerExecutor")
    private final ThreadPoolTaskExecutor chatWorkerExecutor;

    public ConnectionLoginHandler(
            SocketIOServer socketIOServer,
            ConnectedUsers connectedUsers,
            UserRooms userRooms,
            RoomJoinHandler roomJoinHandler,
            RoomLeaveHandler roomLeaveHandler,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper,
            @Qualifier("chatWorkerExecutor") ThreadPoolTaskExecutor chatWorkerExecutor) {
        this.socketIOServer = socketIOServer;
        this.connectedUsers = connectedUsers;
        this.userRooms = userRooms;
        this.roomJoinHandler = roomJoinHandler;
        this.roomLeaveHandler = roomLeaveHandler;
        this.objectMapper = objectMapper;
        this.chatWorkerExecutor = chatWorkerExecutor;

        // Register gauge metric for concurrent users
        Gauge.builder("socketio.concurrent.users", connectedUsers::size)
                .description("Current number of concurrent Socket.IO users")
                .register(meterRegistry);
    }

    /**
     * auth 처리가 선행되어야 해서 @OnConnect 대신 별도 메서드로 구현
     */
    public void onConnect(SocketIOClient client, SocketUser user) {
        String userId = user.id();

        try {
            chatWorkerExecutor.execute(() -> handleConnectAsync(client, user, userId));
        } catch (RejectedExecutionException e) {
            client.sendEvent(ERROR, Map.of(
                    "message", "요청이 많아 연결을 처리할 수 없습니다."
            ));
        }
    }

    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {
        String userId = getUserId(client);
        try {
            chatWorkerExecutor.execute(() -> handleDisconnectAsync(client, userId));
        } catch (RejectedExecutionException e) {
            log.warn("Disconnect handling rejected for user {}", userId);
        }

    }

    private void handleConnectAsync(SocketIOClient client, SocketUser user, String userId) {
        try {
            notifyDuplicateLogin(client, userId);
            client.set("user", user);

            userRooms.get(userId).forEach(roomId -> roomJoinHandler.handleJoinRoom(client, roomId));

            connectedUsers.set(userId, user);

            log.info("Socket.IO user connected: {} ({}) - Total concurrent users: {}",
                    getUserName(client), userId, connectedUsers.size());

            client.joinRooms(Set.of("user:" + userId, "room-list"));

        } catch (Exception e) {
            log.error("Error handling Socket.IO connection", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "연결 처리 중 오류가 발생했습니다."
            ));
        }
    }

    private void handleDisconnectAsync(SocketIOClient client, String userId) {
        String userName = getUserName(client);

        try {
            if (userId == null) {
                return;
            }

            userRooms.get(userId).forEach(roomId -> roomLeaveHandler.handleLeaveRoom(client, roomId));

            connectedUsers.del(userId);

            client.leaveRooms(Set.of("user:" + userId, "room-list"));
            client.del("user");
            client.disconnect();

            log.info("Socket.IO user disconnected: {} ({}) - Total concurrent users: {}",
                    userName, userId, connectedUsers.size());
        } catch (Exception e) {
            log.error("Error handling Socket.IO disconnection", e);
            client.sendEvent(ERROR, Map.of(
                "message", "연결 종료 처리 중 오류가 발생했습니다."
            ));
        }
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

    /**
     * socketIOServer.getRoomOperations("user:" + userId) 로 처리 변경.
     */
    private void notifyDuplicateLogin(SocketIOClient client, String userId) {
        var socketUser = connectedUsers.get(userId);
        if (socketUser == null) {
            return;
        }

        if (client.getSessionId().toString().equals(socketUser.socketId())) {
            return;
        }

        log.info("Duplicate login detected. Notifying existing session for user: {}", userId);

        String userAgent = client.getHandshakeData().getHttpHeaders().get("User-Agent");

        socketIOServer.getRoomOperations("user:" + userId)
                .sendEvent(DUPLICATE_LOGIN, Map.of(
                        "type", "new_login_attempt",
                        // User-Agent가 null인 경우 "Unknown Device"로 대체
                        "deviceInfo", userAgent != null ? userAgent : "Unknown Device",
                        "ipAddress", client.getRemoteAddress().toString(),
                        "timestamp", System.currentTimeMillis()
                ));

    }
}
