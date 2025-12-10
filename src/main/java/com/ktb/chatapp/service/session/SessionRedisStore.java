package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class SessionRedisStore implements SessionStore {

    private final RedisTemplate<String, Session> sessionRedisTemplate;

    private static final String KEY_PREFIX = "session:";
    private static final Duration SESSION_TTL = Duration.ofHours(24); // 세션 만료 24시간

    private String getKey(String userId) {
        return KEY_PREFIX + userId;
    }

    @Override
    public Optional<Session> findByUserId(String userId) {
        Session session = sessionRedisTemplate.opsForValue().get(getKey(userId));
        return Optional.ofNullable(session);
    }

    @Override
    public Session save(Session session) {
        String key = getKey(session.getUserId());
        sessionRedisTemplate.opsForValue().set(key, session, SESSION_TTL);
        return session;
    }

    @Override
    public void delete(String userId, String sessionId) {
        String key = getKey(userId);
        Session session = sessionRedisTemplate.opsForValue().get(key);

        if (session != null && sessionId.equals(session.getSessionId())) {
            sessionRedisTemplate.delete(key);
        }
    }

    @Override
    public void deleteAll(String userId) {
        sessionRedisTemplate.delete(getKey(userId));
    }
}