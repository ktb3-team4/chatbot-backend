package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.FileRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageResponseMapper {

    private final FileRepository fileRepository;

    /**
     * Message 엔티티를 MessageResponse DTO로 변환
     * 별도의 User 조회 없이 Message 내의 임베딩 정보를 사용
     */
    public MessageResponse mapToMessageResponse(Message message) {
        MessageResponse.MessageResponseBuilder builder = MessageResponse.builder()
                .id(message.getId())
                .content(message.getContent())
                .type(message.getType())
                .timestamp(message.toTimestampMillis())
                .roomId(message.getRoomId())
                .reactions(message.getReactions() != null ?
                        message.getReactions() : new HashMap<>())
                .readers(message.getReaders() != null ?
                        message.getReaders() : new ArrayList<>());

        // 임베딩된 정보로 Sender 설정
        if (message.getSenderId() != null) {
            builder.sender(UserResponse.builder()
                    .id(message.getSenderId())
                    .name(message.getSenderName() != null ? message.getSenderName() : "알 수 없음")
                    .email("")
                    .profileImage(message.getSenderProfileImage() != null ? message.getSenderProfileImage() : "")
                    .build());
        }

        // AI 메시지인 경우 처리
        if (message.getType() == com.ktb.chatapp.model.MessageType.ai && message.getAiType() != null) {
            builder.sender(UserResponse.builder()
                    .id("AI")
                    .name(message.getAiType().getName())
                    .profileImage("") // AI 전용 이미지 URL이 있다면 여기에 설정
                    .build());
        }

        // 파일 정보 설정
        Optional.ofNullable(message.getFileId())
                .flatMap(fileRepository::findById)
                .map(FileResponse::from)
                .ifPresent(builder::file);

        if (message.getMetadata() != null) {
            builder.metadata(message.getMetadata());
        }

        return builder.build();
    }
}