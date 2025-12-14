package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.ChatMessageRequest;
import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageContent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.*;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.util.BannedWordChecker;
import com.ktb.chatapp.websocket.socketio.ai.AiService;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.service.RateLimitService;
import com.ktb.chatapp.service.RateLimitCheckResult;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ChatMessageHandler {
    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final FileRepository fileRepository;
    private final AiService aiService;
    private final SessionService sessionService;
    private final BannedWordChecker bannedWordChecker;
    private final RateLimitService rateLimitService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    @Qualifier("chatWorkerExecutor")
    private final ThreadPoolTaskExecutor chatWorkerExecutor;

    @Qualifier("chatPersistenceExecutor")
    private final ThreadPoolTaskExecutor chatPersistenceExecutor;

    @OnEvent(CHAT_MESSAGE)
    public void handleChatMessage(SocketIOClient client, ChatMessageRequest data) {

        if (data == null) {
            client.sendEvent(ERROR, Map.of("code", "MESSAGE_ERROR", "message", "메시지 데이터가 없습니다."));
            return;
        }

        SocketUser socketUser = getUser(client);
        if (socketUser == null) {
            client.sendEvent(ERROR, Map.of("code", "SESSION_EXPIRED", "message", "세션이 만료되었습니다."));
            return;
        }

        chatWorkerExecutor.execute(() -> processMessageAsync(client, data, socketUser));
    }

    private void processMessageAsync(SocketIOClient client, ChatMessageRequest data, SocketUser socketUser) {
        Timer.Sample timerSample = Timer.start(meterRegistry);

        try {
            SessionValidationResult validation = sessionService.validateSession(socketUser.id(), socketUser.authSessionId());
            if (!validation.isValid()) {
                recordError("session_expired");
                client.sendEvent(ERROR, Map.of("code", "SESSION_EXPIRED", "message", "세션이 만료되었습니다."));
                timerSample.stop(createTimer("error", "session_expired"));
                return;
            }

            RateLimitCheckResult rateLimitResult = rateLimitService.checkRateLimit(socketUser.id(), 10000, Duration.ofMinutes(1));
            if (!rateLimitResult.allowed()) {
                recordError("rate_limit_exceeded");
                client.sendEvent(ERROR, Map.of(
                        "code", "RATE_LIMIT_EXCEEDED",
                        "message", "메시지 전송 횟수 제한을 초과했습니다.",
                        "retryAfter", rateLimitResult.retryAfterSeconds()
                ));
                timerSample.stop(createTimer("error", "rate_limit"));
                return;
            }

            User sender = userRepository.findById(socketUser.id()).orElse(null);
            if (sender == null) {
                recordError("user_not_found");
                client.sendEvent(ERROR, Map.of("code", "MESSAGE_ERROR", "message", "User not found"));
                timerSample.stop(createTimer("error", "user_not_found"));
                return;
            }

            String roomId = data.getRoom();
            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null || !room.getParticipantIds().contains(socketUser.id())) {
                recordError("room_access_denied");
                client.sendEvent(ERROR, Map.of("code", "MESSAGE_ERROR", "message", "채팅방 접근 권한이 없습니다."));
                timerSample.stop(createTimer("error", "room_access_denied"));
                return;
            }

            MessageContent messageContent = data.getParsedContent();

            if (bannedWordChecker.containsBannedWord(messageContent.getTrimmedContent())) {
                recordError("banned_word");
                client.sendEvent(ERROR, Map.of("code", "MESSAGE_REJECTED", "message", "금칙어가 포함된 메시지는 전송할 수 없습니다."));
                timerSample.stop(createTimer("error", "banned_word"));
                return;
            }

            String messageType = data.getMessageType();

            Message message = switch (messageType) {
                case "file" -> handleFileMessage(roomId, sender, messageContent, data.getFileData());
                case "text" -> handleTextMessage(roomId, sender, messageContent);
                default -> throw new IllegalArgumentException("Unsupported message type: " + messageType);
            };

            if (message == null) {
                timerSample.stop(createTimer("ignored", messageType));
                return;
            }

            CompletableFuture
                    .supplyAsync(() -> messageRepository.save(message), chatPersistenceExecutor)
                    .thenAccept(savedMessage -> {
                        socketIOServer.getRoomOperations(roomId)
                                .sendEvent(MESSAGE, createMessageResponse(savedMessage, sender));

                        aiService.handleAIMentions(roomId, socketUser.id(), messageContent);

                        recordMessageSuccess(messageType);
                        timerSample.stop(createTimer("success", messageType));
                    })
                    .exceptionally(e -> {
                        recordError("exception");
                        log.error("Message persistence error", e);
                        client.sendEvent(ERROR, Map.of("code", "MESSAGE_ERROR", "message", "메시지 전송 중 오류가 발생했습니다."));
                        timerSample.stop(createTimer("error", "exception"));
                        return null;
                    });

        } catch (Exception e) {
            recordError("exception");
            log.error("Message handling error", e);
            client.sendEvent(ERROR, Map.of("code", "MESSAGE_ERROR", "message", "메시지 전송 중 오류가 발생했습니다."));
            timerSample.stop(createTimer("error", "exception"));
        }
    }

    private Message handleFileMessage(String roomId, User sender, MessageContent messageContent, Map<String, Object> fileData) {
        if (fileData == null) {
            throw new IllegalArgumentException("파일 데이터가 올바르지 않습니다.");
        }

        // 1. 프론트엔드에서 전달받은 파일 메타데이터 추출
        String filename = (String) fileData.get("filename");         // S3 Key
        String originalname = (String) fileData.get("originalname");
        String mimetype = (String) fileData.get("mimetype");
        String url = (String) fileData.get("url");                   // CloudFront URL

        // 숫자는 Integer로 올 수 있으므로 Number로 형변환 후 long 처리
        long size = 0;
        if (fileData.get("size") instanceof Number) {
            size = ((Number) fileData.get("size")).longValue();
        }

        // 기존에는 ID로 조회했지만, 이제는 받은 정보로 새로 생성합니다.
        File newFile = File.builder()
                .filename(filename)
                .originalname(originalname)
                .mimetype(mimetype)
                .size(size)
                .path(url)              // 프론트에서 받은 URL 저장
                .user(sender.getId())   // 업로더 ID
                .uploadDate(LocalDateTime.now())
                .build();

        File savedFile = fileRepository.save(newFile); // DB에 저장하고 ID 생성

        // 3. Message 생성 및 파일 정보 연결
        Message message = new Message();
        message.setRoomId(roomId);

        message.setSenderId(sender.getId());
        message.setSenderName(sender.getName());
        message.setSenderProfileImage(sender.getProfileImage());

        message.setType(MessageType.file);
        message.setFileId(savedFile.getId());
        message.setContent(messageContent.getTrimmedContent());
        message.setTimestamp(LocalDateTime.now());
        message.setMentions(messageContent.aiMentions());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileType", savedFile.getMimetype());
        metadata.put("fileSize", savedFile.getSize());
        metadata.put("originalName", savedFile.getOriginalname());
        metadata.put("url", savedFile.getPath());
        message.setMetadata(metadata);

        return message;
    }

    private Message handleTextMessage(String roomId, User sender, MessageContent messageContent) {
        if (messageContent.isEmpty()) {
            return null; // 빈 메시지는 무시
        }

        Message message = new Message();
        message.setRoomId(roomId);

        message.setSenderId(sender.getId());
        message.setSenderName(sender.getName());
        message.setSenderProfileImage(sender.getProfileImage());

        message.setContent(messageContent.getTrimmedContent());
        message.setType(MessageType.text);
        message.setTimestamp(LocalDateTime.now());
        message.setMentions(messageContent.aiMentions());

        return message;
    }

    private SocketUser getUser(SocketIOClient client) {
        Object value = client.get("user");
        if (value == null) {
            return null;
        }
        if (value instanceof SocketUser socketUser) {
            return socketUser;
        }
        try {
            return objectMapper.convertValue(value, SocketUser.class);
        } catch (Exception e) {
            log.warn("Failed to convert session user data to SocketUser: {}", e.getMessage());
            return null;
        }
    }

    private MessageResponse createMessageResponse(Message message, User sender) {
        var messageResponse = new MessageResponse();
        messageResponse.setId(message.getId());
        messageResponse.setRoomId(message.getRoomId());
        messageResponse.setContent(message.getContent());
        messageResponse.setType(message.getType());
        messageResponse.setTimestamp(message.toTimestampMillis());
        messageResponse.setReactions(message.getReactions() != null ? message.getReactions() : Collections.emptyMap());
        messageResponse.setSender(UserResponse.from(sender));
        messageResponse.setMetadata(message.getMetadata());

        if (message.getFileId() != null) {
            fileRepository.findById(message.getFileId())
                    .ifPresent(file -> messageResponse.setFile(FileResponse.from(file)));
        }

        return messageResponse;
    }

    // Metrics helper methods
    private Timer createTimer(String status, String messageType) {
        return Timer.builder("socketio.messages.processing.time")
                .description("Socket.IO message processing time")
                .tag("status", status)
                .tag("message_type", messageType)
                .register(meterRegistry);
    }

    private void recordMessageSuccess(String messageType) {
        Counter.builder("socketio.messages.total")
                .description("Total Socket.IO messages processed")
                .tag("status", "success")
                .tag("message_type", messageType)
                .register(meterRegistry)
                .increment();
    }

    private void recordError(String errorType) {
        Counter.builder("socketio.messages.errors")
                .description("Socket.IO message processing errors")
                .tag("error_type", errorType)
                .register(meterRegistry)
                .increment();
    }
}
