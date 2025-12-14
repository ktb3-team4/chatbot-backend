package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.RoomCreatedEvent;
import com.ktb.chatapp.event.RoomUpdatedEvent;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 채팅방 목록 조회 (캐싱 + 개인화 적용)
     * 1. 캐시된 공통 데이터(Generic Data)를 조회
     * 2. 현재 사용자에 맞게 isCreator 필드를 계산하여 반환
     */
    public RoomsResponse getAllRoomsWithPagination(
            com.ktb.chatapp.dto.PageRequest pageRequest, String userEmail) {

        try {
            // 현재 사용자 정보 조회 (isCreator 계산을 위해 ID 필요)
            User currentUser = userRepository.findByEmail(userEmail)
                    .orElseThrow(() -> new RuntimeException("User not found: " + userEmail));
            String currentUserId = currentUser.getId();

            // 정렬 설정
            if (!pageRequest.isValidSortField()) {
                pageRequest.setSortField("createdAt");
            }
            if (!pageRequest.isValidSortOrder()) {
                pageRequest.setSortOrder("desc");
            }

            Sort.Direction direction = "desc".equals(pageRequest.getSortOrder())
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;

            String sortField = pageRequest.getSortField();
            if ("participantsCount".equals(sortField)) {
                sortField = "participantIds";
            }

            PageRequest springPageRequest = PageRequest.of(
                    pageRequest.getPage(),
                    pageRequest.getPageSize(),
                    Sort.by(direction, sortField)
            );

            // 데이터 조회
            RoomsResponse response;
            boolean isCacheable = pageRequest.getPage() == 0 &&
                    (pageRequest.getSearch() == null || pageRequest.getSearch().trim().isEmpty());

            if (isCacheable) {
                // 캐시된 메서드 호출
                response = getCachedDefaultRooms(springPageRequest);
            } else {
                // 직접 조회
                response = getRoomsGeneric(springPageRequest, pageRequest.getSearch());
            }

            // 사용자별 isCreator 값 업데이트
            List<RoomResponse> personalizedData = response.getData().stream()
                    .map(room -> personalizeRoomResponse(room, currentUserId))
                    .collect(Collectors.toList());

            return RoomsResponse.builder()
                    .success(response.isSuccess())
                    .data(personalizedData)
                    .metadata(response.getMetadata())
                    .build();

        } catch (Exception e) {
            log.error("방 목록 조회 에러", e);
            return RoomsResponse.builder()
                    .success(false)
                    .data(List.of())
                    .build();
        }
    }

    /**
     * 캐시 메서드: 공통 데이터 조회
     * - isCreator는 항상 false로 저장됨
     * - 키: 'default' (모든 유저가 공유)
     */
    @Cacheable(value = "rooms", key = "'default'")
    public RoomsResponse getCachedDefaultRooms(org.springframework.data.domain.Pageable pageable) {
        return getRoomsGeneric(pageable, null);
    }

    /**
     * 실제 DB 조회 및 Generic 매핑
     */
    private RoomsResponse getRoomsGeneric(org.springframework.data.domain.Pageable pageable, String search) {
        Page<Room> roomPage;
        if (search != null && !search.trim().isEmpty()) {
            roomPage = roomRepository.findByNameContainingIgnoreCase(search.trim(), pageable);
        } else {
            roomPage = roomRepository.findAll(pageable);
        }

        Set<String> allUserIds = new HashSet<>();
        for (Room room : roomPage.getContent()) {
            if (room.getCreator() != null) {
                allUserIds.add(room.getCreator());
            }
            if (room.getParticipantIds() != null) {
                allUserIds.addAll(room.getParticipantIds());
            }
        }

        // 모든 User 데이터를 한 번에 조회하여 맵에 저장 (I/O 횟수 대폭 감소)
        Map<String, User> userMap = userRepository.findAllById(allUserIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user, (u1, u2) -> u1)); // 중복 키 처리 추가

        // 맵을 사용하여 매핑
        List<RoomResponse> roomResponses = roomPage.getContent().stream()
                .map(room -> mapToGenericRoomResponse(room, userMap))
                .collect(Collectors.toList());

        PageMetadata metadata = PageMetadata.builder()
                .total(roomPage.getTotalElements())
                .page(pageable.getPageNumber())
                .pageSize(pageable.getPageSize())
                .totalPages(roomPage.getTotalPages())
                .hasMore(roomPage.hasNext())
                .currentCount(roomResponses.size())
                .sort(PageMetadata.SortInfo.builder()
                        .field(pageable.getSort().stream().findFirst().map(Sort.Order::getProperty).orElse("createdAt"))
                        .order(pageable.getSort().stream().findFirst().map(o -> o.getDirection().name().toLowerCase()).orElse("desc"))
                        .build())
                .build();

        return RoomsResponse.builder()
                .success(true)
                .data(roomResponses)
                .metadata(metadata)
                .build();
    }

    // Health Check
    public HealthResponse getHealthStatus() {
        try {
            long startTime = System.currentTimeMillis();
            boolean isMongoConnected = false;
            long latency = 0;

            try {
                roomRepository.findOneForHealthCheck();
                long endTime = System.currentTimeMillis();
                latency = endTime - startTime;
                isMongoConnected = true;
            } catch (Exception e) {
                log.warn("MongoDB 연결 확인 실패", e);
                isMongoConnected = false;
            }

            LocalDateTime lastActivity = roomRepository.findMostRecentRoom()
                    .map(Room::getCreatedAt)
                    .orElse(null);

            Map<String, HealthResponse.ServiceHealth> services = new HashMap<>();
            services.put("database", HealthResponse.ServiceHealth.builder()
                    .connected(isMongoConnected)
                    .latency(latency)
                    .build());

            return HealthResponse.builder()
                    .success(true)
                    .services(services)
                    .lastActivity(lastActivity)
                    .build();

        } catch (Exception e) {
            log.error("Health check 실행 중 에러 발생", e);
            return HealthResponse.builder()
                    .success(false)
                    .services(new HashMap<>())
                    .build();
        }
    }

    @CacheEvict(value = "rooms", key = "'default'")
    public Room createRoom(CreateRoomRequest createRoomRequest, String name) {
        User creator = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        Room room = new Room();
        room.setName(createRoomRequest.getName().trim());
        room.setCreator(creator.getId());
        room.getParticipantIds().add(creator.getId());

        if (createRoomRequest.getPassword() != null && !createRoomRequest.getPassword().isEmpty()) {
            room.setHasPassword(true);
            room.setPassword(passwordEncoder.encode(createRoomRequest.getPassword()));
        }

        Room savedRoom = roomRepository.save(room);

        try {
            // 이벤트 발행 시에는 Generic 정보를 보냄
            RoomResponse roomResponse = mapToGenericRoomResponse(savedRoom);
            eventPublisher.publishEvent(new RoomCreatedEvent(this, roomResponse));
        } catch (Exception e) {
            log.error("roomCreated 이벤트 발행 실패", e);
        }

        return savedRoom;
    }
    private RoomResponse mapToGenericRoomResponse(Room room) {
        User creator = null;
        if (room.getCreator() != null) {
            creator = userRepository.findById(room.getCreator()).orElse(null);
        }

        // 참가자 목록 일괄 조회
        List<User> participants = userRepository.findAllById(room.getParticipantIds());

        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName() != null ? room.getName() : "제목 없음")
                .hasPassword(room.isHasPassword())
                .creator(creator != null ? UserResponse.builder()
                        .id(creator.getId())
                        .name(creator.getName() != null ? creator.getName() : "알 수 없음")
                        .email(creator.getEmail() != null ? creator.getEmail() : "")
                        .profileImage(creator.getProfileImage() != null ? creator.getProfileImage() : "")
                        .build() : null)
                .participants(participants.stream()
                        .filter(p -> p != null && p.getId() != null)
                        .map(p -> UserResponse.builder()
                                .id(p.getId())
                                .name(p.getName() != null ? p.getName() : "알 수 없음")
                                .email(p.getEmail() != null ? p.getEmail() : "")
                                .profileImage(p.getProfileImage() != null ? p.getProfileImage() : "")
                                .build())
                        .collect(Collectors.toList()))
                .createdAtDateTime(room.getCreatedAt())
                .isCreator(false)
                .recentMessageCount(0) // ⬅️ 0으로 고정
                .build();
    }

    @Cacheable(value = "room", key = "#roomId")
    public Optional<Room> findRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    @Caching(evict = {
            @CacheEvict(value = "room", key = "#roomId"),
            @CacheEvict(value = "rooms", key = "'default'")
    })
    public Room joinRoom(String roomId, String password, String name) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return null;
        }

        Room room = roomOpt.get();
        User user = userRepository.findByEmail(name)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + name));

        if (room.isHasPassword()) {
            if (password == null || !passwordEncoder.matches(password, room.getPassword())) {
                throw new RuntimeException("비밀번호가 일치하지 않습니다.");
            }
        }

        if (!room.getParticipantIds().contains(user.getId())) {
            room.getParticipantIds().add(user.getId());
            room = roomRepository.save(room);
        }

        try {
            RoomResponse roomResponse = mapToGenericRoomResponse(room);
            eventPublisher.publishEvent(new RoomUpdatedEvent(this, roomId, roomResponse));
        } catch (Exception e) {
            log.error("roomUpdate 이벤트 발행 실패", e);
        }

        return room;
    }

    private RoomResponse mapToGenericRoomResponse(Room room, Map<String, User> userMap) {
        if (room == null) return null;

        // Map에서 Creator 조회 (N+1 제거)
        User creator = userMap.get(room.getCreator());

        // Map에서 Participants 조회 (N+1 제거)
        List<UserResponse> participants = room.getParticipantIds().stream()
                .map(userMap::get)
                .filter(Objects::nonNull) // null 사용자 필터링
                .filter(p -> p.getId() != null)
                .map(p -> UserResponse.builder()
                        .id(p.getId())
                        .name(p.getName() != null ? p.getName() : "알 수 없음")
                        .email(p.getEmail() != null ? p.getEmail() : "")
                        .profileImage(p.getProfileImage() != null ? p.getProfileImage() : "") // Profile Image 추가
                        .build())
                .collect(Collectors.toList());

        return RoomResponse.builder()
                .id(room.getId())
                .name(room.getName() != null ? room.getName() : "제목 없음")
                .hasPassword(room.isHasPassword())
                // Creator 매핑 시 Map에서 조회한 User 사용
                .creator(creator != null ? UserResponse.builder()
                        .id(creator.getId())
                        .name(creator.getName() != null ? creator.getName() : "알 수 없음")
                        .email(creator.getEmail() != null ? creator.getEmail() : "")
                        .profileImage(creator.getProfileImage() != null ? creator.getProfileImage() : "") // Profile Image 추가
                        .build() : null)
                .participants(participants)
                .createdAtDateTime(room.getCreatedAt())
                .isCreator(false)
                .recentMessageCount(0) // ⬅️ 0으로 고정
                .build();
    }

    // 개인화 매핑: Generic 응답에 현재 사용자 기준 isCreator 주입
    private RoomResponse personalizeRoomResponse(RoomResponse generic, String currentUserId) {
        boolean isCreator = generic.getCreator() != null && generic.getCreator().getId().equals(currentUserId);

        return RoomResponse.builder()
                .id(generic.getId())
                .name(generic.getName())
                .hasPassword(generic.isHasPassword())
                .creator(generic.getCreator())
                .participants(generic.getParticipants())
                .createdAtDateTime(generic.getCreatedAtDateTime())
                .recentMessageCount(generic.getRecentMessageCount())
                .isCreator(isCreator)
                .build();
    }
}