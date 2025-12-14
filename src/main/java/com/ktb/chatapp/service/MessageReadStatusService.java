package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBatch;
import org.redisson.api.RSet;
import org.redisson.api.RSetAsync;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MongoTemplate mongoTemplate;
    private final RedissonClient redissonClient;

    private static final String READ_STATUS_KEY = "chat:read-status:buffer";

    @Value("${chatapp.read-status.batch-size:200}")
    private int batchSize;

    @Value("${chatapp.read-status.max-batches-per-run:5}")
    private int maxBatchesPerRun;

    /**
     * 메시지 읽음 처리를 요청합니다. (Redis에만 저장하고 즉시 리턴)
     */
    public void updateReadStatus(List<String> messageIds, String userId) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }

        // Redisson Batch를 사용하여 파이프라인 최적화
        RBatch batch = redissonClient.createBatch();
        // StringCodec을 사용하여 일반 문자열로 저장
        RSetAsync<String> set = batch.getSet(READ_STATUS_KEY, StringCodec.INSTANCE);

        for (String messageId : messageIds) {
            String entry = messageId + ":" + userId;
            set.addAsync(entry);
        }
        batch.execute();
    }

    /**
     * Redis에 쌓인 읽음 처리 요청을 DB에 일괄 반영합니다.
     * 고부하 시 과도한 bulk write를 방지하기 위해 배치 크기와 반복 횟수를 제한합니다.
     */
    @Scheduled(fixedDelayString = "${chatapp.read-status.flush-delay-ms:5000}")
    public void flushReadStatusToDb() {
        if (batchSize <= 0 || maxBatchesPerRun <= 0) {
            log.warn("Read status flush skipped due to invalid config. batchSize={}, maxBatchesPerRun={}", batchSize, maxBatchesPerRun);
            return;
        }

        RSet<String> set = redissonClient.getSet(READ_STATUS_KEY, StringCodec.INSTANCE);

        int totalEntries = 0;
        int totalMessages = 0;

        for (int i = 0; i < maxBatchesPerRun; i++) {
            Set<String> popped = set.removeRandom(batchSize);
            if (popped == null || popped.isEmpty()) {
                break;
            }

            Map<String, Set<String>> messageToReaders = new HashMap<>();

            for (String entry : popped) {
                String[] parts = entry.split(":");
                if (parts.length == 2) {
                    String messageId = parts[0];
                    String userId = parts[1];
                    messageToReaders
                            .computeIfAbsent(messageId, k -> new HashSet<>())
                            .add(userId);
                }
            }

            if (messageToReaders.isEmpty()) {
                continue;
            }

            try {
                BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Message.class);
                LocalDateTime now = LocalDateTime.now();

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

                bulkOps.execute();
                totalEntries += popped.size();
                totalMessages += messageToReaders.size();

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
