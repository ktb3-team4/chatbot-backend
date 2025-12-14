package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.RoomService;
import com.ktb.chatapp.service.UserService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 방 입장 처리 핸들러
 * 채팅방 입장, 참가자 관리, 초기 메시지 로드 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomJoinHandler {

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final UserRooms userRooms;
    private final MessageLoader messageLoader;
    private final MessageResponseMapper messageResponseMapper;
    private final RoomLeaveHandler roomLeaveHandler;
    private final ObjectMapper objectMapper;
    private final RoomService roomService;
    private final UserService userService;

    @Qualifier("chatWorkerExecutor")
    private final ThreadPoolTaskExecutor chatWorkerExecutor;

    @OnEvent(JOIN_ROOM)
    public void handleJoinRoom(SocketIOClient client, String roomId) {
        // 1. 빠른 유효성 검사 (I/O 작업 없음)
        String userId = getUserId(client);
        if (userId == null) {
            client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Unauthorized"));
            return;
        }

        // 2. I/O와 블로킹이 발생하는 핵심 로직을 작업자 스레드에 위임하고 즉시 리턴
        chatWorkerExecutor.execute(() -> processJoinRoom(client, roomId, userId));
    }

    private void processJoinRoom(SocketIOClient client, String roomId, String userId) {
        String userName = getUserName(client);

        try {
            if (userService.findUserById(userId).isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "User not found"));
                return;
            }

            // [수정 1: RoomService를 사용하여 캐시된 방 정보 조회]
            Optional<Room> roomOpt = roomService.findRoomById(roomId);
            if (roomOpt.isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }

            // 이미 해당 방에 참여 중인지 확인
            if (userRooms.isInRoom(userId, roomId)) {
                log.debug("User {} already in room {}", userId, roomId);
                client.joinRoom(roomId);
                client.sendEvent(JOIN_ROOM_SUCCESS, Map.of("roomId", roomId));
                return;
            }

            // MongoDB의 $addToSet 연산자를 사용한 원자적 업데이트
            roomRepository.addParticipant(roomId, userId);

            // Join socket room and add to user's room set
            client.joinRoom(roomId);
            userRooms.add(userId, roomId);

            // ... (생략: 입장 메시지 생성 및 저장)
            Message joinMessage = Message.builder()
                    .roomId(roomId)
                    .content(userName + "님이 입장하였습니다.")
                    .type(MessageType.system)
                    .timestamp(LocalDateTime.now())
                    .mentions(new ArrayList<>())
                    .isDeleted(false)
                    .reactions(new HashMap<>())
                    .readers(new ArrayList<>())
                    .metadata(new HashMap<>())
                    .build();

            joinMessage = messageRepository.save(joinMessage);

            // 초기 메시지 로드
            FetchMessagesRequest req = new FetchMessagesRequest(roomId, 30, null);
            FetchMessagesResponse messageLoadResult = messageLoader.loadMessages(req, userId);

            // 업데이트된 room 다시 조회하여 최신 participantIds 가져오기 (RoomService 사용)
            Optional<Room> updatedRoomOpt = roomService.findRoomById(roomId);
            if (updatedRoomOpt.isEmpty()) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }

            // 참가자 정보 조회
            List<UserResponse> participants = updatedRoomOpt.get().getParticipantIds()
                    .stream()
                    .map(userService::findUserById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(UserResponse::from)
                    .toList();

            JoinRoomSuccessResponse response = JoinRoomSuccessResponse.builder()
                    .roomId(roomId)
                    .participants(participants)
                    .messages(messageLoadResult.getMessages())
                    .hasMore(messageLoadResult.isHasMore())
                    .activeStreams(Collections.emptyList())
                    .build();

            client.sendEvent(JOIN_ROOM_SUCCESS, response);

            // 입장 메시지 브로드캐스트
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(MESSAGE, messageResponseMapper.mapToMessageResponse(joinMessage));

            // 참가자 목록 업데이트 브로드캐스트
            socketIOServer.getRoomOperations(roomId)
                    .sendEvent(PARTICIPANTS_UPDATE, participants);

            log.info("User {} joined room {} successfully. Message count: {}, hasMore: {}",
                    userName, roomId, messageLoadResult.getMessages().size(), messageLoadResult.isHasMore());

        } catch (Exception e) {
            log.error("Error processing joinRoom", e);
            client.sendEvent(JOIN_ROOM_ERROR, Map.of(
                    "message", e.getMessage() != null ? e.getMessage() : "채팅방 입장에 실패했습니다."
            ));
        }
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

    private String getUserId(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.id() : null;
    }

    private String getUserName(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.name() : null;
    }
}
