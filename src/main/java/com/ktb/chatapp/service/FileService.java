package com.ktb.chatapp.service;


public interface FileService {

    /**
     * [채팅 파일 삭제용]
     * DB File ID 기준으로 삭제 (S3 객체 + DB 레코드)
     */
    boolean deleteFile(String fileId, String requesterId);

    /**
     * [프로필 이미지 삭제용]
     * URL 기준으로 S3 객체를 삭제한다.
     */
    void deleteFileByUrl(String fileUrl);
}
