package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import static java.util.Collections.emptyList;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageLoader {

    private final MessageRepository messageRepository;
    private final MessageResponseMapper messageResponseMapper;
    private final MessageReadStatusService messageReadStatusService;

    // MessageLoader 프록시를 주입받아 Cacheable 메서드를 호출
    private final ObjectProvider<MessageLoader> selfProvider; // <--- 자기 호출 문제 해결

    private static final int BATCH_SIZE = 30;
    private static final int MAX_LIMIT = 100;

    public FetchMessagesResponse loadMessages(FetchMessagesRequest data, String userId) {
        // 프록시 인스턴스 획득
        MessageLoader self = selfProvider.getObject();

        try {
            int effectiveLimit = Math.min(data.limit(BATCH_SIZE), MAX_LIMIT);
            // 'before' 타임스탬프가 없거나 0인 경우 (최초 로드 요청)
            if (data.before() == null || data.before() == 0) {
                FetchMessagesRequest cacheKey = new FetchMessagesRequest(data.roomId(), effectiveLimit, 0L);

                // 프록시를 통해 Cacheable 메서드 호출
                return self.loadInitialMessagesCached(cacheKey, userId);
            }

            // 'before'가 있는 경우 (페이지네이션 요청) - 항상 DB 조회
            return loadMessagesInternal(data.roomId(), effectiveLimit, data.before(LocalDateTime.now()), userId);
        } catch (Exception e) {
            log.error("Error loading messages for room {}", data.roomId(), e);
            return FetchMessagesResponse.builder()
                    .messages(emptyList())
                    .hasMore(false)
                    .build();
        }
    }

    /**
     * 초기 메시지 로드 (가장 최근 메시지) - Redis 캐싱 적용
     * 이 메서드는 반드시 주입된 프록시를 통해 호출되어야 합니다.
     */
    @Cacheable(value = "messages_first_page", key = "#data.roomId() + ':' + #data.limit() + ':' + #userId")
    public FetchMessagesResponse loadInitialMessagesCached(FetchMessagesRequest data, String userId) {
        // 캐시 히트/미스에 관계없이 실제 DB 조회 로직을 호출 (최신 시간 기준)
        int effectiveLimit = Math.min(data.limit(BATCH_SIZE), MAX_LIMIT);
        return loadMessagesInternal(data.roomId(), effectiveLimit, LocalDateTime.now(), userId);
    }

    // 실제 DB I/O 및 응답 매핑을 담당하는 메서드
    private FetchMessagesResponse loadMessagesInternal(
            String roomId,
            int limit,
            LocalDateTime before,
            String userId) {
        int fetchLimit = limit + 1;
        Pageable pageable = PageRequest.of(0, fetchLimit, Sort.by("timestamp").descending());

        Slice<Message> messageSlice = messageRepository
                .findByRoomIdAndIsDeletedAndTimestampBefore(roomId, false, before, pageable);

        List<Message> fetchedMessages = messageSlice.getContent();
        boolean hasMore = fetchedMessages.size() > limit;

        List<Message> messages = hasMore ? fetchedMessages.subList(0, limit) : fetchedMessages;
        List<Message> sortedMessages = messages.reversed();

        if (userId != null && !sortedMessages.isEmpty()) {
            var messageIds = sortedMessages.stream().map(Message::getId).toList(); // <-- toList() 적용
            messageReadStatusService.bufferRead(messageIds, userId, roomId);
        }

        List<MessageResponse> messageResponses = sortedMessages.stream()
                .map(messageResponseMapper::mapToMessageResponse)
                .toList();

        return FetchMessagesResponse.builder()
                .messages(messageResponses)
                .hasMore(hasMore)
                .build();
    }
}
