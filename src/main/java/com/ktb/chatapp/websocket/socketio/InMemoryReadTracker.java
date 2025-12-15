package com.ktb.chatapp.websocket.socketio;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InMemoryReadTracker {

    private final Map<String, Map<String, Set<String>>> roomReads = new ConcurrentHashMap<>();
    private final AtomicLong pendingEntries = new AtomicLong(0);
    private final long maxPendingEntries;

    public InMemoryReadTracker(@Value("${chatapp.read-status.max-pending-entries:50000}") long maxPendingEntries) {
        this.maxPendingEntries = Math.max(1000, maxPendingEntries);
    }

    public void update(String roomId, List<String> messageIds, String userId) {
        if (roomId == null || roomId.isBlank() || messageIds == null || messageIds.isEmpty() || userId == null) {
            return;
        }

        Map<String, Set<String>> messageMap = roomReads.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
        for (String messageId : messageIds) {
            if (messageId == null || messageId.isBlank()) {
                continue;
            }
            Set<String> readers = messageMap.computeIfAbsent(messageId, k -> ConcurrentHashMap.newKeySet());
            boolean added = readers.add(userId);
            if (added) {
                long current = pendingEntries.incrementAndGet();
                if (current > maxPendingEntries) {
                    readers.remove(userId);
                    pendingEntries.decrementAndGet();
                    log.warn("Read tracker at capacity ({}). Dropping new entry room={} message={} user={}",
                            maxPendingEntries, roomId, messageId, userId);
                    break;
                }
            }
        }
    }

    public Map<String, Map<String, Set<String>>> takeBatch(int maxMessagesTotal) {
        if (maxMessagesTotal <= 0) {
            return Collections.emptyMap();
        }

        Map<String, Map<String, Set<String>>> batch = new HashMap<>();
        long removedCount = 0;

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
                    Set<String> readers = msgEntry.getValue();
                    pickedMessages.put(msgEntry.getKey(), new HashSet<>(readers));
                    msgIter.remove();
                    removedCount += readers.size();
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

        if (removedCount > 0) {
            long after = pendingEntries.addAndGet(-removedCount);
            if (after < 0) {
                pendingEntries.set(0);
            }
        }

        return batch;
    }
}
