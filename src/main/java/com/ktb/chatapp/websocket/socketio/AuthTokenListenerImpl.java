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
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    private final RedissonClient redissonClient;

    private static final String AUTH_BACKOFF_KEY_PREFIX = "socketio:auth:backoff:";
    private static final long AUTH_BACKOFF_MS = 1500;

    @Override
    public AuthTokenResult getAuthTokenResult(Object _authToken, SocketIOClient client) {
        try {
            var authToken = (Map<?, ?>) _authToken;
            String token = authToken.get("token") != null ? authToken.get("token").toString() : null;
            String sessionId = authToken.get("sessionId") != null ? authToken.get("sessionId").toString() : null;

            if (token == null || sessionId == null) {
                log.warn("Missing authentication credentials in Socket.IO handshake - token: {}, sessionId: {}",
                        token != null, sessionId != null);
                return new AuthTokenResult(false, "Authentication error");
            }

            if (isBackoffActive(sessionId)) {
                return new AuthTokenResult(false, Map.of(
                        "message", "Retry after refresh",
                        "code", "AUTH_BACKOFF"
                ));
            }

            String userId;
            try {
                userId = jwtService.extractUserId(token);
            } catch (JwtException e) {
                log.warn("Socket.IO token validation failed: {}", e.getMessage());
                activateBackoff(sessionId);
                return new AuthTokenResult(false, Map.of("message", "Invalid token"));
            }

            // Validate session using SessionService
            SessionValidationResult validationResult =
                    sessionService.validateSession(userId, sessionId);

            if (!validationResult.isValid()) {
                log.warn("Session validation failed: {} - {}", validationResult.getError(), validationResult.getMessage());
                connectedUsers.del(userId);
                activateBackoff(sessionId);
                return new AuthTokenResult(false, Map.of(
                        "message", validationResult.getMessage(),
                        "code", validationResult.getError()
                ));
            }

            // Load user from database
            User user = userService.findUserById(userId).orElse(null);
            if (user == null) {
                log.warn("User not found for Socket.IO auth: {}", userId);
                return new AuthTokenResult(false, Map.of("message", "User not found"));
            }

            log.info("Socket.IO connection authorized for user: {} ({})", user.getName(), userId);
            
            var socketUser = new SocketUser(user.getId(), user.getName(), sessionId, client.getSessionId().toString());
            socketIOChatHandlerProvider.getObject().onConnect(client, socketUser);
            return AuthTokenResult.AuthTokenResultSuccess;
        } catch (Exception e) {
            log.error("Socket.IO authentication error: {}", e.getMessage(), e);
            return new AuthTokenResult(false, Map.of("message", e.getMessage()));
        }
    }

    private boolean isBackoffActive(String sessionId) {
        if (sessionId == null) return false;
        RBucket<Boolean> bucket = redissonClient.getBucket(AUTH_BACKOFF_KEY_PREFIX + sessionId);
        Boolean val = bucket.get();
        return val != null && val;
    }

    private void activateBackoff(String sessionId) {
        if (sessionId == null) return;
        RBucket<Boolean> bucket = redissonClient.getBucket(AUTH_BACKOFF_KEY_PREFIX + sessionId);
        bucket.set(true, AUTH_BACKOFF_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
