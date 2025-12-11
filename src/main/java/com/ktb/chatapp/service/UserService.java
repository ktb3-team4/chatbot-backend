package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.UpdateProfileRequest;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final FileService fileService;
    /**
     * 현재 사용자 프로필 조회
     */
    public UserResponse getCurrentUserProfile(String email) {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
        return UserResponse.from(user);
    }

    /**
     * 사용자 프로필 업데이트
     */
    public UserResponse updateUserProfile(String email, UpdateProfileRequest request) {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        user.setName(request.getName());

        // 프론트엔드에서 S3 업로드 후 보낸 URL을 저장
        if (request.getProfileImage() != null) {
//            if (request.getProfileImage().isEmpty() && hasProfileImage(user)) {
//                fileService.deleteFileByUrl(user.getProfileImage());
//            }
            user.setProfileImage(request.getProfileImage());
        }

        user.setUpdatedAt(LocalDateTime.now());

        User updatedUser = userRepository.save(user);
        log.info("사용자 프로필 업데이트 완료 - ID: {}, Name: {}, Image: {}",
                user.getId(), request.getName(), request.getProfileImage());

        return UserResponse.from(updatedUser);
    }
    /**
     * 특정 사용자 프로필 조회
     */
    public UserResponse getUserProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        return UserResponse.from(user);
    }

    /**
     * 기존 프로필 이미지 삭제 (유저 엔티티 기반)
     */
    private void deleteOldProfileImage(User user) {
        if (!hasProfileImage(user)) {
            return;
        }

        // S3에서 파일 삭제
        //fileService.deleteFileByUrl(user.getProfileImage());

        // DB 정보 초기화
        user.setProfileImage("");
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("프로필 이미지 삭제 완료: {}", user.getId());
    }

    private boolean hasProfileImage(User user) {
        return user.getProfileImage() != null && !user.getProfileImage().isBlank();
    }

    /**
     * 프로필 이미지 삭제 (URL 정보만 지움)
     */
//    public void deleteProfileImage(String email) {
//        User user = userRepository.findByEmail(email.toLowerCase())
//                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
//
//        if (hasProfileImage(user)) {
//            deleteOldProfileImage(user);
//        }
//    }

    /**
     * 회원 탈퇴 처리
     */
    public void deleteUserAccount(String email) {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

//        if (hasProfileImage(user)) {
//            fileService.deleteFileByUrl(user.getProfileImage());
//        }

        userRepository.delete(user);
        log.info("회원 탈퇴 완료 - User ID: {}", user.getId());
    }
}