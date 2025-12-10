package com.ktb.chatapp.websocket.socketio.store;

import com.corundumstudio.socketio.store.Store;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class RedissonStore implements Store {

    private final RMap<String, Object> map;

    public RedissonStore(UUID sessionId, RedissonClient redisson) {
        // 세션별로 별도의 Redis Map 사용
        this.map = redisson.getMap("socketio:store:" + sessionId.toString());
        // 24시간 후 만료
        this.map.expire(24, TimeUnit.HOURS);
    }

    @Override
    public void set(String key, Object value) {
        map.put(key, value);
    }

    @Override
    public <T> T get(String key) {
        return (T) map.get(key);
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