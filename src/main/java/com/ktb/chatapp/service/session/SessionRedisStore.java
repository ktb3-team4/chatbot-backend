package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.convert.DurationStyle;
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
    private static final Duration SESSION_TTL = DurationStyle.detectAndParse(Session.SESSION_TTL);

    private String getKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    @Override
    public Optional<Session> findByUserId(String userId) {
        log.warn("findByUserId called, which is deprecated in multi-session model. Returning empty for userId: {}", userId);
        return Optional.empty();
    }

    public Optional<Session> findBySessionId(String sessionId) {
        Session session = sessionRedisTemplate.opsForValue().get(getKey(sessionId));
        return Optional.ofNullable(session);
    }

    @Override
    public Session save(Session session) {
        String key = getKey(session.getSessionId());
        sessionRedisTemplate.opsForValue().set(key, session, SESSION_TTL);
        return session;
    }

    @Override
    public void delete(String userId, String sessionId) {
        String key = getKey(sessionId);
        sessionRedisTemplate.delete(key);
    }

    @Override
    public void deleteAll(String userId) {
        log.warn("deleteAll(userId) called, which is inefficient/unsupported in the multi-session Redis key structure. No action taken for userId: {}", userId);
    }
}
