package com.ktb.chatapp.websocket.socketio.store;

import com.corundumstudio.socketio.store.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class RedissonStore implements Store {

    private final RMap<String, String> map;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedissonStore(UUID sessionId, RedissonClient redisson) {
        this.map = redisson.getMap("socketio:store:" + sessionId);
        this.map.expire(1, TimeUnit.DAYS);
    }

    @Override
    public void set(String key, Object value) {
        try {
            map.put(key, objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T get(String key) {
        try {
            String json = map.get(key);
            if (json == null) return null;

            return (T) objectMapper.readValue(json, Object.class);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public <T> T get(String key, Class<T> type) {
        try {
            String json = map.get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean has(String key) {
        return map.containsKey(key);
    }

    @Override
    public void del(String key) {
        map.remove(key);
    }
}