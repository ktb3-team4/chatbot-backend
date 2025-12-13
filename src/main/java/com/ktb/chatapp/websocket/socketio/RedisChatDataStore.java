package com.ktb.chatapp.websocket.socketio;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.codec.TypedJsonJacksonCodec;

import java.util.Optional;

@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {

    private static final String REDIS_MAP_NAME = "chatDataStore";
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        RMap<String, Object> map = redissonClient.getMap(REDIS_MAP_NAME, new TypedJsonJacksonCodec(String.class, Object.class, objectMapper));
        Object value = map.get(key);

        if (value == null) {
            return Optional.empty();
        }

        try {
            if (type.isInstance(value)) {
                return Optional.of(type.cast(value));
            }
            return Optional.of(objectMapper.convertValue(value, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        RMap<String, Object> map = redissonClient.getMap(REDIS_MAP_NAME, new TypedJsonJacksonCodec(String.class, Object.class, objectMapper));
        map.put(key, value);
    }


    @Override
    public void delete(String key) {
        RMap<String, Object> map = redissonClient.getMap(REDIS_MAP_NAME, new TypedJsonJacksonCodec(String.class, Object.class, objectMapper));
        map.remove(key);
    }

    @Override
    public int size() {
        RMap<String, Object> map = redissonClient.getMap(REDIS_MAP_NAME, new TypedJsonJacksonCodec(String.class, Object.class, objectMapper));
        return map.size();
    }
}
