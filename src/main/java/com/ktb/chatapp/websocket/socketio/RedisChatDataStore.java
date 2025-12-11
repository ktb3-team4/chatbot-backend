package com.ktb.chatapp.websocket.socketio;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.Optional;

/**
 * Redis-based implementation of ChatDataStore using Redisson.
 * Provides distributed storage for chat-related data across multiple server nodes.
 */
@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {

    private static final String REDIS_MAP_NAME = "chatDataStore";

    private final RedissonClient redissonClient;

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        RMap<String, Object> map = redissonClient.getMap(REDIS_MAP_NAME);
        Object value = map.get(key);

        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(type.cast(value));
        } catch (ClassCastException e) {
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        RMap<String, Object> map = redissonClient.getMap(REDIS_MAP_NAME);
        map.put(key, value);
    }

    @Override
    public void delete(String key) {
        RMap<String, Object> map = redissonClient.getMap(REDIS_MAP_NAME);
        map.remove(key);
    }

    @Override
    public int size() {
        RMap<String, Object> map = redissonClient.getMap(REDIS_MAP_NAME);
        return map.size();
    }
}
