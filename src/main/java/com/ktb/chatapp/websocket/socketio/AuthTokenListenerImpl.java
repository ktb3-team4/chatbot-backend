package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.AuthTokenResult;
import com.corundumstudio.socketio.SocketIOClient;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.service.JwtService;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.service.UserService;
import com.ktb.chatapp.websocket.socketio.handler.ConnectionLoginHandler;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Socket.IO Authorization Handler
 * socket.handshake.auth.token과 sessionId를 처리한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AuthTokenListenerImpl implements AuthTokenListener {

    private final JwtService jwtService;
    private final SessionService sessionService;
    private final UserService userService;
    private final ObjectProvider<ConnectionLoginHandler> socketIOChatHandlerProvider;
    private final ConnectedUsers connectedUsers;

    @Qualifier("chatWorkerExecutor")
    private final ThreadPoolTaskExecutor chatWorkerExecutor;

    @Override
    public AuthTokenResult getAuthTokenResult(Object _authToken, SocketIOClient client) {
        try {
            var authToken = (Map<?, ?>) _authToken;
            String token = authToken.get("token") != null ? authToken.get("token").toString() : null;
            String sessionId = authToken.get("sessionId") != null ? authToken.get("sessionId").toString() : null;

            log.info("Socket.IO handshake received. token exists: {}, sessionId: {}",
                    token != null, sessionId);

            if (token == null || sessionId == null) {
                log.warn("Missing authentication credentials in Socket.IO handshake - token: {}, sessionId: {}",
                        token != null, sessionId != null);
                return new AuthTokenResult(false, "Authentication error: Missing token or session ID");
            }

            String userId;
            try {
                // JWT parsing is non-blocking (in-memory)
                userId = jwtService.extractUserId(token);
            } catch (JwtException e) {
                log.warn("Socket.IO token validation failed: {}", e.getMessage());
                return new AuthTokenResult(false, Map.of("message", "Invalid token"));
            }

            // 🚨 START: Offload Blocking I/O to chatWorkerExecutor
            CompletableFuture<AuthTokenResult> authFuture = CompletableFuture.supplyAsync(() -> {
                // 1. 세션 검증 (Redis I/O)
                SessionValidationResult validationResult =
                        sessionService.validateSession(userId, sessionId);

                if (!validationResult.isValid()) {
                    connectedUsers.del(userId);
                    // RuntimeException을 던져서 CompletableFuture.get()에서 처리되도록 합니다.
                    throw new RuntimeException(
                            "SESSION_VALIDATION_FAILED:" + validationResult.getMessage() + ":" + validationResult.getError()
                    );
                }

                // 2. 사용자 로드 (MongoDB/Cache I/O)
                User user = userService.findUserById(userId)
                        .orElseThrow(() -> new RuntimeException("USER_NOT_FOUND:User not found"));

                // 3. 성공 처리 및 Connect Handler 호출 (논블로킹)
                var socketUser = new SocketUser(user.getId(), user.getName(), sessionId, client.getSessionId().toString());
                socketIOChatHandlerProvider.getObject().onConnect(client, socketUser);

                log.info("Socket.IO connection authorized for user: {} ({}) on worker thread", user.getName(), userId);

                return AuthTokenResult.AuthTokenResultSuccess;

            }, chatWorkerExecutor); // ⬅️ I/O 전용 스레드 풀 사용

            // 🚨 END: Socket.IO 스레드는 최대 4초만 블로킹하며 결과를 기다립니다.
            return authFuture.get(4, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            log.warn("Auth process timed out (4s) for user: {} on Socket.IO thread", client.getHandshakeData().getUrl());
            return new AuthTokenResult(false, Map.of("message", "Authentication timed out", "code", "SERVER_BUSY"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Auth process interrupted: {}", client.getHandshakeData().getUrl());
            return new AuthTokenResult(false, Map.of("message", "Authentication process interrupted"));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage();

            if (message != null && message.startsWith("SESSION_VALIDATION_FAILED:")) {
                // SESSION_VALIDATION_FAILED:세션이 만료되었습니다.:SESSION_EXPIRED 패턴 파싱
                String[] parts = message.split(":");
                String msg = parts.length > 1 ? parts[1] : "Session validation failed";
                String code = parts.length > 2 ? parts[2] : "SESSION_EXPIRED";
                log.warn("Session validation failed (Offloaded): {} - {}", code, msg);
                return new AuthTokenResult(false, Map.of("message", msg, "code", code));
            } else if (message != null && message.startsWith("USER_NOT_FOUND:")) {
                String msg = message.split(":")[1];
                log.warn("User not found (Offloaded): {}", msg);
                return new AuthTokenResult(false, Map.of("message", msg));
            } else {
                log.error("Socket.IO authentication error: {}", message, cause);
                return new AuthTokenResult(false, Map.of("message", "Internal server error during auth"));
            }
        }
    }
}