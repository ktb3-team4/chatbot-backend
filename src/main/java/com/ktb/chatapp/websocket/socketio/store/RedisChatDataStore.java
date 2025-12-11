package com.ktb.chatapp.websocket.socketio.store;

import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * [최적화] Redis 기반 데이터 저장소
 * LocalChatDataStore(메모리) 대신 이걸 사용하여 멀티 서버 환경을 지원합니다.
 */
@Component
@Primary // ✅ 중요: LocalChatDataStore 대신 이게 주입됩니다.
@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {

    private final RedissonClient redissonClient;
    // 데이터 찌꺼기 방지용 만료 시간 (24시간)
    private static final long TTL_HOURS = 24;

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        T value = bucket.get();
        return Optional.ofNullable(value);
    }

    @Override
    public void set(String key, Object value) {
        RBucket<Object> bucket = redissonClient.getBucket(key);
        bucket.set(value, TTL_HOURS, TimeUnit.HOURS);
    }

    @Override
    public void delete(String key) {
        RBucket<Object> bucket = redissonClient.getBucket(key);
        bucket.delete();
    }

    @Override
    public int size() {
        // Redis 전체 키 개수 조회는 성능에 치명적이므로 0 리턴 (로직에 영향 없음)
        return 0;
    }
}