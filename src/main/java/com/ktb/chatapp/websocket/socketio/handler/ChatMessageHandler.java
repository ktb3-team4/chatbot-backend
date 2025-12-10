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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
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

    @Qualifier("chatWorkerExecutor")
    private final ThreadPoolTaskExecutor chatWorkerExecutor;

    @OnEvent(CHAT_MESSAGE)
    public void handleChatMessage(SocketIOClient client, ChatMessageRequest data) {

        if (data == null) {
            client.sendEvent(ERROR, Map.of("code", "MESSAGE_ERROR", "message", "메시지 데이터가 없습니다."));
            return;
        }

        var socketUser = (SocketUser) client.get("user");
        if (socketUser == null) {
            client.sendEvent(ERROR, Map.of("code", "SESSION_EXPIRED", "message", "세션이 만료되었습니다."));
            return;
        }

        chatWorkerExecutor.execute(() -> processMessageAsync(client, data, socketUser));
    }

    private void processMessageAsync(SocketIOClient client, ChatMessageRequest data, SocketUser socketUser) {
        Timer.Sample timerSample = Timer.start(meterRegistry); // 타이머 시작

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

            // [수정] sender 정보를 조회하여 이후 로직에 활용 (임베딩을 위해)
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

            Message savedMessage = messageRepository.save(message);

            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGE, createMessageResponse(savedMessage, sender));

            aiService.handleAIMentions(roomId, socketUser.id(), messageContent);

            sessionService.updateLastActivity(socketUser.id());

            recordMessageSuccess(messageType);
            timerSample.stop(createTimer("success", messageType));

        } catch (Exception e) {
            recordError("exception");
            log.error("Message handling error", e);
            client.sendEvent(ERROR, Map.of("code", "MESSAGE_ERROR", "message", "메시지 전송 중 오류가 발생했습니다."));
            timerSample.stop(createTimer("error", "exception"));
        }
    }

    // sender 객체를 받아 임베딩 필드 설정
    private Message handleFileMessage(String roomId, User sender, MessageContent messageContent, Map<String, Object> fileData) {
        if (fileData == null || fileData.get("_id") == null) {
            throw new IllegalArgumentException("파일 데이터가 올바르지 않습니다.");
        }

        String fileId = (String) fileData.get("_id");
        File file = fileRepository.findById(fileId).orElse(null);

        if (file == null || !file.getUser().equals(sender.getId())) {
            throw new IllegalStateException("파일을 찾을 수 없거나 접근 권한이 없습니다.");
        }

        Message message = new Message();
        message.setRoomId(roomId);

        // Sender 정보 임베딩
        message.setSenderId(sender.getId());
        message.setSenderName(sender.getName());
        message.setSenderProfileImage(sender.getProfileImage());

        message.setType(MessageType.file);
        message.setFileId(fileId);
        message.setContent(messageContent.getTrimmedContent());
        message.setTimestamp(LocalDateTime.now());
        message.setMentions(messageContent.aiMentions());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileType", file.getMimetype());
        metadata.put("fileSize", file.getSize());
        metadata.put("originalName", file.getOriginalname());
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