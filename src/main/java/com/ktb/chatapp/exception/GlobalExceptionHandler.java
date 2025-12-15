package com.ktb.chatapp.exception;

import com.ktb.chatapp.dto.ApiErrorCode;
import com.ktb.chatapp.dto.StandardResponse;
import com.ktb.chatapp.dto.ValidationError;
import jakarta.servlet.http.HttpServletRequest;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 전역 예외 처리기
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @Value("${spring.profiles.active:production}")
    private String activeProfile;

    /**
     * 인증 예외 처리
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<StandardResponse<Object>> handleAuthenticationException(
            AuthenticationException ex, HttpServletRequest request) {

        log.warn("인증 실패: {} - {}", request.getRequestURI(), ex.getMessage());

        StandardResponse<Object> response = StandardResponse.error(ApiErrorCode.UNAUTHORIZED);
        response.setPath(request.getRequestURI());
        return ResponseEntity.status(ApiErrorCode.UNAUTHORIZED.getHttpStatus()).body(response);
    }

    /**
     * 인가 예외 처리 (권한 없음)
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<StandardResponse<Object>> handleAccessDeniedException(
            AccessDeniedException ex, HttpServletRequest request) {

        log.warn("접근 권한 없음: {} - {}", request.getRequestURI(), ex.getMessage());

        StandardResponse<Object> response = StandardResponse.error(ApiErrorCode.FORBIDDEN);
        response.setPath(request.getRequestURI());
        return ResponseEntity.status(ApiErrorCode.FORBIDDEN.getHttpStatus()).body(response);
    }

    /**
     * 유효성 검증 실패 처리
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<StandardResponse<Object>> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<ValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> ValidationError.builder()
                        .field(fieldError.getField())
                        .message(fieldError.getDefaultMessage())
                        .build())
                .collect(Collectors.toList());

        log.warn("유효성 검증 실패: {}", errors);

        StandardResponse<Object> response = StandardResponse.validationError(errors);
        response.setPath(request.getRequestURI());
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 파일 업로드 크기 초과 예외 처리
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<StandardResponse<Object>> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {

        log.warn("파일 업로드 크기 초과: {}", ex.getMessage());

        StandardResponse<Object> response = StandardResponse.error(ApiErrorCode.FILE_TOO_LARGE);
        response.setPath(request.getRequestURI());
        return ResponseEntity.status(ApiErrorCode.FILE_TOO_LARGE.getHttpStatus()).body(response);
    }

    /**
     * 클라이언트 연결 끊김 예외 처리 (Broken pipe, ClientAbort)
     * - 클라이언트가 응답을 받기 전에 연결을 끊었을 때 발생하는 예상된 예외입니다.
     * - 서버 오류가 아니므로 WARN 레벨로 처리하여 로그 노이즈를 줄이고 부하 테스트 로그 오염을 막습니다.
     */
    @ExceptionHandler({
            AsyncRequestNotUsableException.class,
            org.apache.catalina.connector.ClientAbortException.class // Tomcat specific exception
    })
    public ResponseEntity<Void> handleClientDisconnect(Exception ex, HttpServletRequest request) {
        String rootMessage = (ex.getCause() != null) ? ex.getCause().getMessage() : ex.getMessage();

        if (rootMessage != null && rootMessage.contains("Broken pipe")) {
            log.warn("클라이언트 연결 끊김 감지 (Broken Pipe): {} - {}", request.getRequestURI(), rootMessage);
        } else {
            log.warn("클라이언트 연결 끊김 감지: {} - {}", request.getRequestURI(), ex.getMessage());
        }

        // 이미 연결이 끊겼으므로 응답을 보낼 수 없지만, Spring의 에러 카운트를 막기 위해 204를 반환합니다.
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * 일반적인 Runtime 예외 처리
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<StandardResponse<Object>> handleRuntimeException(
            RuntimeException ex, HttpServletRequest request) {

        log.error("런타임 예외: {} - {}", request.getRequestURI(), ex.getMessage(), ex);

        StandardResponse<Object> response = StandardResponse.error(ApiErrorCode.INTERNAL_SERVER_ERROR);
        response.setPath(request.getRequestURI());
        if (isDevelopmentProfile()) {
            response.setStack(getStackTrace(ex));
        }

        return ResponseEntity.status(ApiErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus()).body(response);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<StandardResponse<Object>> handleNoResourceFoundException() {
        return ResponseEntity.notFound().build();
    }

    /**
     * 모든 예외의 최종 처리기
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<StandardResponse<Object>> handleGenericException(
            Exception ex, HttpServletRequest request) {

        log.error("예상치 못한 예외: {} - {}", request.getRequestURI(), ex.getMessage(), ex);

        StandardResponse<Object> response = StandardResponse.error(ApiErrorCode.INTERNAL_SERVER_ERROR);
        response.setPath(request.getRequestURI());
        if (isDevelopmentProfile()) {
            response.setStack(getStackTrace(ex));
        }

        return ResponseEntity.status(ApiErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus()).body(response);
    }

    private boolean isDevelopmentProfile() {
        String normalized = activeProfile.trim().toLowerCase();
        return "dev".equals(normalized);
    }

    private String getStackTrace(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }
}