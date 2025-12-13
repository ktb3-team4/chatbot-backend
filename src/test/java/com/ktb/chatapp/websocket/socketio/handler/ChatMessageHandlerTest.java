//package com.ktb.chatapp.websocket.socketio.handler;
//
//import com.corundumstudio.socketio.SocketIOClient;
//import com.corundumstudio.socketio.SocketIOServer;
//import com.ktb.chatapp.dto.ChatMessageRequest;
//import com.ktb.chatapp.model.Room;
//import com.ktb.chatapp.model.User;
//import com.ktb.chatapp.repository.FileRepository;
//import com.ktb.chatapp.repository.MessageRepository;
//import com.ktb.chatapp.repository.RoomRepository;
//import com.ktb.chatapp.repository.UserRepository;
//import com.ktb.chatapp.service.RateLimitCheckResult;
//import com.ktb.chatapp.service.RateLimitService;
//import com.ktb.chatapp.service.SessionService;
//import com.ktb.chatapp.service.SessionValidationResult;
//import com.ktb.chatapp.util.BannedWordChecker;
//import com.ktb.chatapp.websocket.socketio.SocketUser;
//import com.ktb.chatapp.websocket.socketio.ai.AiService;
//import io.micrometer.core.instrument.MeterRegistry;
//import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
//import java.util.HashSet;
//import java.util.Map;
//import java.util.Optional;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.ArgumentCaptor;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
//
//import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.eq;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//class ChatMessageHandlerTest {
//
//    @Mock private SocketIOServer socketIOServer;
//    @Mock private MessageRepository messageRepository;
//    @Mock private RoomRepository roomRepository;
//    @Mock private UserRepository userRepository;
//    @Mock private FileRepository fileRepository;
//    @Mock private AiService aiService;
//    @Mock private SessionService sessionService;
//    @Mock private BannedWordChecker bannedWordChecker;
//    @Mock private RateLimitService rateLimitService;
//    @Mock private ThreadPoolTaskExecutor chatWorkerExecutor; // 추가된 Mock
//
//    private MeterRegistry meterRegistry = new SimpleMeterRegistry();
//
//    private ChatMessageHandler handler;
//
//    @BeforeEach
//    void setUp() {
//        // 비동기 Executor가 작업을 받으면 즉시 실행하도록 Stubbing
//        doAnswer(invocation -> {
//            Runnable task = invocation.getArgument(0);
//            task.run();
//            return null;
//        }).when(chatWorkerExecutor).execute(any(Runnable.class));
//
//        handler =
//                new ChatMessageHandler(
//                        socketIOServer,
//                        messageRepository,
//                        roomRepository,
//                        userRepository,
//                        fileRepository,
//                        aiService,
//                        sessionService,
//                        bannedWordChecker,
//                        rateLimitService,
//                        meterRegistry,
//                        chatWorkerExecutor); // 생성자에 Mock Executor 주입
//    }
//
//    @Test
//    void handleChatMessage_blocksMessagesContainingBannedWords() {
//        // Given
//        SocketIOClient client = mock(SocketIOClient.class);
//        SocketUser socketUser = new SocketUser("user-1", "tester", "session-1", "socket-1");
//        when(client.get("user")).thenReturn(socketUser);
//
//        // Session Valid Mock
//        SessionValidationResult validResult = SessionValidationResult.valid(null);
//        when(sessionService.validateSession(socketUser.id(), socketUser.authSessionId()))
//                .thenReturn(validResult);
//
//        // Rate Limit Mock
//        RateLimitCheckResult allowedResult = RateLimitCheckResult.allowed(10000, 9999, 60, System.currentTimeMillis() / 1000 + 60, 60);
//        when(rateLimitService.checkRateLimit(eq(socketUser.id()), anyInt(), any()))
//                .thenReturn(allowedResult);
//
//        // User & Room Mock
//        User user = new User();
//        user.setId("user-1");
//        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
//
//        Room room = new Room();
//        room.setId("room-1");
//        room.setParticipantIds(new HashSet<>(java.util.List.of("user-1")));
//        when(roomRepository.findById("room-1")).thenReturn(Optional.of(room));
//
//        // Request with Banned Word
//        ChatMessageRequest request =
//                ChatMessageRequest.builder()
//                        .room("room-1")
//                        .type("text")
//                        .content("bad word")
//                        .build();
//
//        when(bannedWordChecker.containsBannedWord("bad word")).thenReturn(true);
//
//        // When
//        handler.handleChatMessage(client, request);
//
//        // Then
//        // 1. Worker Executor가 호출되었는지 검증
//        verify(chatWorkerExecutor).execute(any(Runnable.class));
//
//        // 2. 금칙어 차단 메시지가 클라이언트로 전송되었는지 검증
//        ArgumentCaptor<Map<String, String>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
//        verify(client).sendEvent(eq(ERROR), payloadCaptor.capture());
//
//        Map<String, String> payload = payloadCaptor.getValue();
//        assertEquals("MESSAGE_REJECTED", payload.get("code"));
//
//        // 3. 메시지가 저장되지 않았는지 검증
//        verifyNoInteractions(messageRepository);
//        verify(socketIOServer, never()).getRoomOperations(any());
//    }
//}