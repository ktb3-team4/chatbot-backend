package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MongoTemplate mongoTemplate;
    private final StringRedisTemplate redisTemplate;

    private static final String READ_STATUS_KEY = "chat:read-status:buffer";

    /**
     * 메시지 읽음 처리를 요청합니다. (Redis에만 저장하고 즉시 리턴)
     */
    public void updateReadStatus(List<String> messageIds, String userId) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        // Redis Set에 "messageId:userId" 형태로 저장
        // 파이프라인을 사용하여 네트워크 왕복 최소화
        redisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
            for (String messageId : messageIds) {
                String entry = messageId + ":" + userId;
                connection.setCommands().sAdd(READ_STATUS_KEY.getBytes(), entry.getBytes());
            }
            return null;
        });
    }

    /**
     * 5초마다 실행되어 Redis에 쌓인 읽음 처리 요청을 DB에 일괄 반영합니다.
     */
    @Scheduled(fixedDelay = 5000)
    public void flushReadStatusToDb() {
        List<String> popped = redisTemplate.opsForSet().pop(READ_STATUS_KEY, 1000);

        if (popped == null || popped.isEmpty()) {
            return;
        }
        Set<String> entries = new HashSet<>(popped);

        Map<String, Set<String>> messageToReaders = new HashMap<>();

        for (String entry : entries) {
            String[] parts = entry.split(":");
            if (parts.length == 2) {
                String messageId = parts[0];
                String userId = parts[1];
                messageToReaders
                        .computeIfAbsent(messageId, k -> new HashSet<>())
                        .add(userId);
            }
        }

        if (messageToReaders.isEmpty()) return;

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
            log.info("Flushed {} read status updates to DB for {} messages", entries.size(), messageToReaders.size());

        } catch (Exception e) {
            log.error("Failed to flush read status to DB", e);
        }
    }

}