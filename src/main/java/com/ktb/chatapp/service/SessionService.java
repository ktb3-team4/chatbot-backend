package com.ktb.chatapp.service;

import com.ktb.chatapp.exception.SessionExpiredException;
import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.session.SessionRedisStore;
import com.ktb.chatapp.service.session.SessionStore;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import static com.ktb.chatapp.model.Session.SESSION_TTL;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionStore sessionStore;
    private final SessionRedisStore sessionRedisStore;
    private final RedissonClient redissonClient;
    public static final long SESSION_TTL_SEC = DurationStyle.detectAndParse(SESSION_TTL).getSeconds();
    private static final long SESSION_TIMEOUT = SESSION_TTL_SEC * 1000;

    private static final long ACTIVITY_UPDATE_THRESHOLD = 20 * 60 * 1000;

    private String getSessionLockKey(String sessionId) {
        return "lock:session:" + sessionId;
    }

    private RLock acquireSessionLock(String sessionId) {
        return redissonClient.getLock(getSessionLockKey(sessionId));
    }

    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private SessionData toSessionData(Session session) {
        return SessionData.builder()
                .userId(session.getUserId())
                .sessionId(session.getSessionId())
                .createdAt(session.getCreatedAt())
                .lastActivity(session.getLastActivity())
                .metadata(session.getMetadata())
                .build();
    }

    public SessionCreationResult createSession(String userId, SessionMetadata metadata) {
        try {

            String sessionId = generateSessionId();
            long now = Instant.now().toEpochMilli();

            Session session = Session.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .createdAt(now)
                    .lastActivity(now)
                    .metadata(metadata)
                    .expiresAt(Instant.now().plusSeconds(SESSION_TTL_SEC))
                    .build();

            session = sessionStore.save(session);

            SessionData sessionData = toSessionData(session);

            return SessionCreationResult.builder()
                    .sessionId(sessionId)
                    .expiresIn(SESSION_TTL_SEC)
                    .sessionData(sessionData)
                    .build();

        } catch (Exception e) {
            log.error("Session creation error for userId: {}", userId, e);
            throw new RuntimeException("세션 생성 중 오류가 발생했습니다.", e);
        }
    }

    public SessionValidationResult validateSession(String userId, String sessionId) {
        try {
            if (userId == null || sessionId == null) {
                log.warn("validateSession called with null parameters: userId={}, sessionId={}", userId, sessionId);
                return SessionValidationResult.invalid("INVALID_PARAMETERS", "유효하지 않은 세션 파라미터");
            }

            Session session = sessionRedisStore.findBySessionId(sessionId).orElse(null);

            if (session == null) {
                log.debug("No session found for sessionId: {}", sessionId);
                return SessionValidationResult.invalid("INVALID_SESSION", "세션을 찾을 수 없습니다.");
            }

            if (!userId.equals(session.getUserId())) {
                log.debug("User ID mismatch for sessionId: {}. Provided: {}, Expected: {}", sessionId, userId, session.getUserId());
                return SessionValidationResult.invalid("INVALID_SESSION", "잘못된 사용자 ID입니다.");
            }

            long now = Instant.now().toEpochMilli();
            if (now - session.getLastActivity() > SESSION_TIMEOUT) {
                log.debug("Session timed out for userId: {}, sessionId: {}", userId, sessionId);
                removeSession(userId, sessionId);
                return SessionValidationResult.invalid("SESSION_EXPIRED", "세션이 만료되었습니다.");
            }

            if (now - session.getLastActivity() >= ACTIVITY_UPDATE_THRESHOLD) {
                session.setLastActivity(now);
                sessionStore.save(session); // TTL refresh for active session
            }

            SessionData sessionData = toSessionData(session);
            return SessionValidationResult.valid(sessionData);

        } catch (Exception e) {
            log.error("Session validation error for userId: {}, sessionId: {}", userId, sessionId, e);
            return SessionValidationResult.invalid("VALIDATION_ERROR", "세션 검증 중 오류가 발생했습니다.");
        }
    }

    public SessionCreationResult refreshSession(String userId, String sessionId, SessionMetadata metadata) {
        RLock lock = acquireSessionLock(sessionId);
        boolean locked = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);

            if (!locked) {
                throw new IllegalStateException("세션 갱신을 위한 잠금 획득에 실패했습니다.");
            }

            SessionValidationResult validation = validateSession(userId, sessionId);
            if (!validation.isValid()) {
                throw new SessionExpiredException(validation.getMessage());
            }

            removeSession(userId, sessionId);
            return createSession(userId, metadata);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("세션 갱신 처리 중 인터럽트되었습니다.", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public void updateLastActivity(String userId) {
        try {
            log.warn("updateLastActivity called with userId only. This feature is unsupported in multi-session model and will not update any session activity for userId: {}", userId);

        } catch (Exception e) {
            log.error("Failed to update session activity for user: {}", userId, e);
        }
    }

    public void removeSession(String userId, String sessionId) {
        try {
            if (sessionId != null) {
                sessionStore.delete(userId, sessionId); // RedisStore에서 sessionId로 삭제됨
            } else {
                sessionStore.deleteAll(userId); // RedisStore에서 no-op 처리됨
            }
        } catch (Exception e) {
            log.error("Session removal error for userId: {}, sessionId: {}", userId, sessionId, e);
            throw new RuntimeException("세션 삭제 중 오류가 발생했습니다.", e);
        }
    }

    public void removeSessionWithLock(String userId, String sessionId) {
        RLock lock = acquireSessionLock(sessionId);
        boolean locked = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);

            if (!locked) {
                throw new IllegalStateException("세션 삭제를 위한 잠금 획득에 실패했습니다.");
            }

            removeSession(userId, sessionId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("세션 삭제 처리 중 인터럽트되었습니다.", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public void removeAllUserSessions(String userId) {
        try {
            sessionStore.deleteAll(userId);
        } catch (Exception e) {
            log.error("Remove all sessions error for userId: {}", userId, e);
            throw new RuntimeException("모든 세션 삭제 중 오류가 발생했습니다.", e);
        }
    }

    void removeSession(String userId) {
        removeSession(userId, null);
    }

    SessionData getActiveSession(String userId) {
        log.warn("getActiveSession called with userId only. This method is invalid in multi-session model. Returning null for userId: {}", userId);
        return null;
    }

    @Async("chatWorkerExecutor")
    public void removeAllUserSessionsAsync(String userId) {
        log.info("Async session removal started for userId: {}", userId);
        removeAllUserSessions(userId);
    }
}
