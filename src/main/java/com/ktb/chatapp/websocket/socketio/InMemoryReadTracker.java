package com.ktb.chatapp.websocket.socketio;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryReadTracker {

    private final Map<String, Map<String, Set<String>>> roomReads = new ConcurrentHashMap<>();

    public void update(String roomId, List<String> messageIds, String userId) {
        if (roomId == null || roomId.isBlank() || messageIds == null || messageIds.isEmpty() || userId == null) {
            return;
        }

        Map<String, Set<String>> messageMap = roomReads.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
        for (String messageId : messageIds) {
            if (messageId == null || messageId.isBlank()) {
                continue;
            }
            messageMap.computeIfAbsent(messageId, k -> ConcurrentHashMap.newKeySet()).add(userId);
        }
    }

    public Map<String, Map<String, Set<String>>> takeBatch(int maxMessagesTotal) {
        if (maxMessagesTotal <= 0) {
            return Collections.emptyMap();
        }

        Map<String, Map<String, Set<String>>> batch = new HashMap<>();

        synchronized (roomReads) {
            int remaining = maxMessagesTotal;
            var roomIter = roomReads.entrySet().iterator();
            while (roomIter.hasNext() && remaining > 0) {
                var roomEntry = roomIter.next();
                Map<String, Set<String>> messageMap = roomEntry.getValue();
                Map<String, Set<String>> pickedMessages = new HashMap<>();

                var msgIter = messageMap.entrySet().iterator();
                while (msgIter.hasNext() && remaining > 0) {
                    var msgEntry = msgIter.next();
                    pickedMessages.put(msgEntry.getKey(), new HashSet<>(msgEntry.getValue()));
                    msgIter.remove();
                    remaining--;
                }

                if (!pickedMessages.isEmpty()) {
                    batch.put(roomEntry.getKey(), pickedMessages);
                }

                if (messageMap.isEmpty()) {
                    roomIter.remove();
                }
            }
        }

        return batch;
    }
}
