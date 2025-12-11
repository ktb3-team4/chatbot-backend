package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.AuthTokenResult;
import com.corundumstudio.socketio.SocketIOClient;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.service.JwtService;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.service.UserService; // ✅ 추가
import com.ktb.chatapp.websocket.socketio.handler.ConnectionLoginHandler;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AuthTokenListenerImpl implements AuthTokenListener {

    private final JwtService jwtService;
    private final SessionService sessionService;
    private final UserService userService; // 캐시된 서비스
    private final ObjectProvider<ConnectionLoginHandler> socketIOChatHandlerProvider;

    @Override
    public AuthTokenResult getAuthTokenResult(Object _authToken, SocketIOClient client) {
        if (!(_authToken instanceof Map)) {
            return new AuthTokenResult(false, Map.of("message", "Invalid auth token format"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> authData = (Map<String, Object>) _authToken;
        String token = (String) authData.get("token");
        String sessionId = (String) authData.get("sessionId");

        if (token == null || sessionId == null) {
            return new AuthTokenResult(false, Map.of("message", "Missing token or sessionId"));
        }

        try {
            // 1. 토큰에서 정보 추출
            String userId = jwtService.extractUserId(token);
            String email = jwtService.extractEmail(token);

            // 2. 세션 검증 - Redis 조회
            SessionValidationResult validationResult =
                    sessionService.validateSession(userId, sessionId);

            if (!validationResult.isValid()) {
                log.error("Session validation failed: {}", validationResult.getMessage());
                return new AuthTokenResult(false, Map.of("message", "Invalid session"));
            }

            // 3. 유저 정보 조회 - 캐시 사용 -> DB 조회 X
            // UserResponse는 DTO이므로 SocketUser 생성에 바로 활용 가능
            UserResponse user = userService.getCurrentUserProfile(email);

            if (user == null) {
                log.error("User not found: {}", email);
                return new AuthTokenResult(false, Map.of("message", "User not found"));
            }

            log.info("Socket.IO connection authorized for user: {} ({})", user.getName(), userId);

            // 4. 소켓 유저 등록
            var socketUser = new SocketUser(user.getId(), user.getName(), sessionId, client.getSessionId().toString());
            socketIOChatHandlerProvider.getObject().onConnect(client, socketUser);

            return AuthTokenResult.AuthTokenResultSuccess;

        } catch (Exception e) {
            log.error("Socket.IO authentication error: {}", e.getMessage(), e);
            return new AuthTokenResult(false, Map.of("message", e.getMessage()));
        }
    }
}