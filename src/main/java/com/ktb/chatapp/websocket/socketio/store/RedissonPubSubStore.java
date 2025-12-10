package com.ktb.chatapp.websocket.socketio.store;

import com.corundumstudio.socketio.store.pubsub.PubSubListener;
import com.corundumstudio.socketio.store.pubsub.PubSubMessage;
import com.corundumstudio.socketio.store.pubsub.PubSubStore;
import com.corundumstudio.socketio.store.pubsub.PubSubType;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

@RequiredArgsConstructor
public class RedissonPubSubStore implements PubSubStore {

    private final RedissonClient redisson;
    private final Long nodeId;

    @Override
    public void publish(PubSubType type, PubSubMessage msg) {
        String name = "socketio:topic:" + type.toString();
        msg.setNodeId(nodeId);
        redisson.getTopic(name).publish(msg);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends PubSubMessage> void subscribe(PubSubType type,
                                                    PubSubListener<T> listener,
                                                    Class<T> clazz) {
        String name = "socketio:topic:" + type.toString();
        RTopic topic = redisson.getTopic(name);

        topic.addListener(clazz, (channel, msg) -> {
            T casted = (T) msg;
            // 같은 노드에서 publish한 메시지는 무시
            if (!nodeId.equals(casted.getNodeId())) {
                listener.onMessage(casted);
            }
        });
    }

    @Override
    public void unsubscribe(PubSubType type) {
        String name = "socketio:topic:" + type.toString();
        redisson.getTopic(name).removeAllListeners();
    }

    @Override
    public void shutdown() {
        // RedissonClient는 외부에서 관리
    }
}
