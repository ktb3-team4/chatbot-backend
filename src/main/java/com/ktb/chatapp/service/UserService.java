package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.ProfileImageResponse;
import com.ktb.chatapp.dto.UpdateProfileRequest;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.util.FileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final FileService fileService;
    private final FileDeletionService fileDeletionService; // ✅ 필수 주입

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Value("${app.profile.image.max-size:5242880}") // 5MB
    private long maxProfileImageSize;

    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp"
    );

    /**
     * 현재 사용자 프로필 조회
     */
    public UserResponse getCurrentUserProfile(String email) {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
        return UserResponse.from(user);
    }

    /**
     * 사용자 프로필 정보 수정 (이름 등)
     */
    @CacheEvict(value = "security_user", key = "#email.toLowerCase()")
    public UserResponse updateUserProfile(String email, UpdateProfileRequest request) {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        user.setName(request.getName());
        user.setUpdatedAt(LocalDateTime.now());

        User updatedUser = userRepository.save(user);
        log.info("사용자 프로필 업데이트 완료 - ID: {}, Name: {}", user.getId(), request.getName());

        return UserResponse.from(updatedUser);
    }

    /**
     * 프로필 이미지 업로드 (기존 이미지 비동기 삭제 포함)
     * ✅ 메서드명 수정: uploadProfileImage
     */
    @CacheEvict(value = "security_user", key = "#email.toLowerCase()")
    public ProfileImageResponse uploadProfileImage(String email, MultipartFile file) {
        // 1. 사용자 조회
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        // 2. 파일 유효성 검증
        validateProfileImageFile(file);

        // 3. 기존 프로필 이미지 삭제 (✅ 비동기 서비스로 변경)
        if (user.getProfileImage() != null && !user.getProfileImage().isEmpty()) {
            // deleteOldProfileImage(user.getProfileImage()); -> 이거 대신 아래 코드 사용
            fileDeletionService.deleteProfileImageAsync(user.getProfileImage());
        }

        // 4. 새 파일 저장
        String profileImageUrl = fileService.storeFile(file, "profiles");

        // 5. DB 업데이트
        user.setProfileImage(profileImageUrl);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("프로필 이미지 업로드 완료 - User ID: {}, File: {}", user.getId(), profileImageUrl);

        return new ProfileImageResponse(
                true,
                "프로필 이미지가 업데이트되었습니다.",
                profileImageUrl
        );
    }

    /**
     * 프로필 이미지 삭제 (프로필 사진만 내리기)
     * ✅ 수정됨: 회원을 탈퇴시키지 않고 사진만 지움
     */
    @CacheEvict(value = "security_user", key = "#email.toLowerCase()")
    public void deleteProfileImage(String email) {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        if (user.getProfileImage() != null && !user.getProfileImage().isEmpty()) {
            // 비동기 파일 삭제 요청
            fileDeletionService.deleteProfileImageAsync(user.getProfileImage());

            // ✅ DB 정보 업데이트 (중요: delete(user)가 아니라 setProfileImage("")여야 함)
            user.setProfileImage("");
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user); // 저장만 함

            log.info("프로필 이미지 삭제 완료 - User ID: {}", user.getId());
        }
    }

    /**
     * 회원 탈퇴 처리
     */
    @CacheEvict(value = "security_user", key = "#email.toLowerCase()", allEntries = true)
    public void deleteUserAccount(String email) {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        // 프로필 이미지가 있으면 비동기 삭제
        if (user.getProfileImage() != null && !user.getProfileImage().isEmpty()) {
            fileDeletionService.deleteProfileImageAsync(user.getProfileImage());
        }

        userRepository.delete(user); // 이건 진짜 탈퇴
        log.info("회원 탈퇴 완료 - User ID: {}", user.getId());
    }

    // 내부 검증 메서드
    private void validateProfileImageFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("빈 파일입니다.");
        }
        if (file.getSize() > maxProfileImageSize) {
            throw new IllegalArgumentException("파일 크기는 5MB를 초과할 수 없습니다.");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.contains("..")) {
            throw new IllegalArgumentException("잘못된 파일명입니다.");
        }
        String extension = FileUtil.getFileExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }
    }
}