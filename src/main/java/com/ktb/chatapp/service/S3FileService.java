package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.repository.FileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
@Service
public class S3FileService implements FileService {

    private final FileRepository fileRepository;
    private final S3Client s3Client;
    private final String bucket;
    private final String cloudFrontDomain;

    public S3FileService(
            FileRepository fileRepository,
            S3Client s3Client,
            @Value("${cloud.aws.s3.bucket}") String bucket,
            @Value("${cdn.cloudfront.domain}") String cloudFrontDomain
    ) {
        this.fileRepository = fileRepository;
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.cloudFrontDomain = cloudFrontDomain;
    }

    /**
     * [채팅용] 파일 삭제 (DB ID 기준)
     */
    @Override
    public boolean deleteFile(String fileId, String requesterId) {
        File fileEntity = fileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다."));

        if (!fileEntity.getUser().equals(requesterId)) {
            throw new RuntimeException("파일 삭제 권한이 없습니다.");
        }
        deleteS3Object(fileEntity.getFilename());

        fileRepository.delete(fileEntity);
        return true;
    }

    /**
     * [프로필용] 파일 삭제 (URL 기준)
     */
    @Override
    public void deleteFileByUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isEmpty()) {
            return;
        }

        // URL에서 S3 Key 추출
        String key = extractKeyFromUrl(fileUrl);
        if (key != null && !key.isBlank()) {
            deleteS3Object(key);
        }
    }

    private void deleteS3Object(String key) {
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3Client.deleteObject(deleteRequest);
            log.info("S3 Object deleted: {}", key);
        } catch (Exception e) {
            log.error("S3 delete failed: {}", e.getMessage());
        }
    }

    private String extractKeyFromUrl(String url) {
        try {
            // URL 디코딩 (공백, 한글 등 처리)
            String decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);

            // CloudFront 도메인 제거
            if (decodedUrl.contains(cloudFrontDomain)) {
                return decodedUrl.replace("https://" + cloudFrontDomain + "/", "")
                        .replace("http://" + cloudFrontDomain + "/", "");
            }
            // S3 기본 URL인 경우 (혹시 모를 하위 호환)
            if (decodedUrl.contains(bucket)) {
                URI uri = URI.create(decodedUrl);
                String path = uri.getPath();
                return path.startsWith("/") ? path.substring(1) : path;
            }
            // URL이 아니라 이미 Key인 경우
            return decodedUrl;
        } catch (Exception e) {
            log.error("Failed to extract key from URL: {}", url);
            return null;
        }
    }

    @Override
    @Cacheable(value = "file_metadata", key = "#fileId", unless = "T(java.util.Optional).empty().equals(#result)")
    public Optional<File> findFileMetadataById(String fileId) {
        return fileRepository.findById(fileId);
    }
}