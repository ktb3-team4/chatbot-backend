package com.ktb.chatapp.websocket.socketio.store;

import com.corundumstudio.socketio.store.Store;
import com.corundumstudio.socketio.store.pubsub.BaseStoreFactory;
import com.corundumstudio.socketio.store.pubsub.PubSubStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RequiredArgsConstructor
public class RedissonStoreFactory extends BaseStoreFactory {

    private final RedissonClient redisson;
    private final Long nodeId = ThreadLocalRandom.current().nextLong();

    @Override
    public Store createStore(UUID sessionId) {
        return new RedissonStore(sessionId, redisson);
    }

    @Override
    public PubSubStore pubSubStore() {
        return new RedissonPubSubStore(redisson, nodeId);
    }

    @Override
    public <K, V> Map<K, V> createMap(String name) {
        return redisson.getMap(name);
    }

    @Override
    public void shutdown() {
        // 외부에서 주입받은 RedissonClient는 여기서 종료하지 않음
    }
}
