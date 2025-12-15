package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.websocket.socketio.InMemoryReadTracker;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MongoTemplate mongoTemplate;
    private final InMemoryReadTracker inMemoryReadTracker;

    @Value("${chatapp.read-status.batch-size:200}")
    private int batchSize;

    @Value("${chatapp.read-status.max-batches-per-run:5}")
    private int maxBatchesPerRun;

    /**
     * In-memory에 읽음 상태를 누적합니다. (핫패스에서 외부 I/O 없음)
     */
    public void bufferRead(List<String> messageIds, String userId, String roomId) {
        inMemoryReadTracker.update(roomId, messageIds, userId);
    }

    /**
     * 인메모리 누적분을 주기적으로 DB에 일괄 반영합니다.
     * 고부하 시 과도한 bulk write를 방지하기 위해 배치 크기와 반복 횟수를 제한합니다.
     */
    @Scheduled(fixedDelayString = "${chatapp.read-status.flush-delay-ms:5000}")
    public void flushReadStatusToDb() {
        if (batchSize <= 0 || maxBatchesPerRun <= 0) {
            log.warn("Read status flush skipped due to invalid config. batchSize={}, maxBatchesPerRun={}", batchSize, maxBatchesPerRun);
            return;
        }

        int totalEntries = 0;
        int totalMessages = 0;

        for (int i = 0; i < maxBatchesPerRun; i++) {
            Map<String, Map<String, Set<String>>> roomBatch = inMemoryReadTracker.takeBatch(batchSize);
            if (roomBatch.isEmpty()) {
                break;
            }

            try {
                BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Message.class);
                LocalDateTime now = LocalDateTime.now();

                for (Map<String, Set<String>> messageToReaders : roomBatch.values()) {
                    for (Map.Entry<String, Set<String>> e : messageToReaders.entrySet()) {
                        String messageId = e.getKey();
                        Set<String> userIds = e.getValue();

                        List<Message.MessageReader> newReaders = new ArrayList<>();
                        for (String uid : userIds) {
                            newReaders.add(Message.MessageReader.builder()
                                    .userId(uid)
                                    .readAt(now)
                                    .build());
                        }

                        Query query = Query.query(Criteria.where("_id").is(messageId));
                        Update update = new Update()
                                .addToSet("readers")
                                .each(newReaders.toArray(new Message.MessageReader[0]));

                        bulkOps.updateOne(query, update);
                    }
                }

                bulkOps.execute();
                for (Map<String, Set<String>> messageMap : roomBatch.values()) {
                    totalMessages += messageMap.size();
                    for (Set<String> users : messageMap.values()) {
                        totalEntries += users.size();
                    }
                }

            } catch (Exception e) {
                log.error("Failed to flush read status to DB", e);
            }
        }

        if (totalEntries > 0) {
            log.info("Flushed {} read status updates to DB for {} messages (batchSize={}, batches={})",
                    totalEntries, totalMessages, batchSize, maxBatchesPerRun);
        }
    }
}
