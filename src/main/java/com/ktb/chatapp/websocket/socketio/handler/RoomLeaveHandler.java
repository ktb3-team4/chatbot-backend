package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List; // List 임포트 추가
import java.util.Map;
import java.util.Optional;
import java.util.Set; // Set 임포트 추가
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 방 퇴장 처리 핸들러
 * 채팅방 퇴장, 스트리밍 세션 종료, 참가자 목록 업데이트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomLeaveHandler {

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final UserRooms userRooms;
    private final MessageResponseMapper messageResponseMapper;

    @OnEvent(LEAVE_ROOM)
    public void handleLeaveRoom(SocketIOClient client, String roomId) {
        try {
            String userId = getUserId(client);
            String userName = getUserName(client);

            if (userId == null) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            if (!userRooms.isInRoom(userId, roomId)) {
                log.debug("User {} is not in room {}", userId, roomId);
                return;
            }

            User user = userRepository.findById(userId).orElse(null);
            Room room = roomRepository.findById(roomId).orElse(null);

            if (user == null || room == null) {
                log.warn("Room {} not found or user {} has no access", roomId, userId);
                return;
            }

            roomRepository.removeParticipant(roomId, userId);

            client.leaveRoom(roomId);
            userRooms.remove(userId, roomId);

            log.info("User {} left room {}", userName, room.getName());

            log.debug("Leave room cleanup - roomId: {}, userId: {}", roomId, userId);

            sendSystemMessage(roomId, userName + "님이 퇴장하였습니다.");
            broadcastParticipantList(roomId);
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(USER_LEFT, Map.of(
                            "userId", userId,
                            "userName", userName
                    ));

        } catch (Exception e) {
            log.error("Error handling leaveRoom", e);
            client.sendEvent(ERROR, Map.of("message", "채팅방 퇴장 중 오류가 발생했습니다."));
        }
    }

    private void sendSystemMessage(String roomId, String content) {
        try {
            Message systemMessage = new Message();
            systemMessage.setRoomId(roomId);
            systemMessage.setContent(content);
            systemMessage.setType(MessageType.system);
            systemMessage.setTimestamp(LocalDateTime.now());
            systemMessage.setMentions(new ArrayList<>());
            systemMessage.setIsDeleted(false);
            systemMessage.setReactions(new HashMap<>());
            systemMessage.setReaders(new ArrayList<>());
            systemMessage.setMetadata(new HashMap<>());

            // 시스템 메시지는 sender 정보가 없으므로 임베딩 필드는 null로 둡니다.

            Message savedMessage = messageRepository.save(systemMessage);

            // [수정] 매퍼 시그니처 변경 반영 (User 인자 제거)
            MessageResponse response = messageResponseMapper.mapToMessageResponse(savedMessage);

            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGE, response);

        } catch (Exception e) {
            log.error("Error sending system message", e);
        }
    }

    private void broadcastParticipantList(String roomId) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return;
        }

        Set<String> participantIds = roomOpt.get().getParticipantIds();
        if (participantIds == null || participantIds.isEmpty()) {
            return;
        }

        // [최적화] findAllById를 사용하여 N+1 쿼리 문제 해결
        List<UserResponse> participantList = userRepository.findAllById(participantIds)
                .stream()
                .map(UserResponse::from)
                .toList();

        if (participantList.isEmpty()) {
            return;
        }

        socketIOServer.getRoomOperations(roomId)
                .sendEvent(PARTICIPANTS_UPDATE, participantList);
    }

    private SocketUser getUserDto(SocketIOClient client) {
        return client.get("user");
    }

    private String getUserId(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.id() : null;
    }

    private String getUserName(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.name() : null;
    }
}