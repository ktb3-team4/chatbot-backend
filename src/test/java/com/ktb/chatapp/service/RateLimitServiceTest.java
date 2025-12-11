package com.ktb.chatapp.service;


// import java.time.Duration;
// import java.time.Instant;
// import java.util.UUID;
// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.Test;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.boot.test.context.SpringBootTest;
// import org.springframework.test.context.TestPropertySource;

// import static org.assertj.core.api.Assertions.assertThat;

// @SpringBootTest
// @TestPropertySource(properties = {
//         "socketio.enabled=false"
// })
// @DisplayName("RateLimitService 통합 테스트")
// class RateLimitServiceTest {

//     @Autowired
//     private RateLimitService rateLimitService;

//     // 테스트 간 데이터 충돌 방지를 위한 고유 ID 생성 메서드
//     private String generateUniqueClientId(String baseIp) {
//         return baseIp + "-" + UUID.randomUUID();
//     }

//     @Test
//     @DisplayName("최초 요청은 허용되고 TTL과 남은 횟수가 갱신된다")
//     void checkRateLimit_AllowsFirstRequest() {
//         int maxRequests = 5;
//         Duration window = Duration.ofSeconds(60);
//         String clientId = generateUniqueClientId("ip:127.0.0.1");

//         long beforeCall = Instant.now().getEpochSecond();
//         RateLimitCheckResult result =
//                 rateLimitService.checkRateLimit(clientId, maxRequests, window);
//         long afterCall = Instant.now().getEpochSecond();

//         assertThat(result.allowed()).isTrue();
//         assertThat(result.limit()).isEqualTo(maxRequests);
//         assertThat(result.remaining()).isEqualTo(maxRequests - 1);
//         assertThat(result.windowSeconds()).isEqualTo(window.getSeconds());

//         assertThat(result.retryAfterSeconds()).isZero();

//         assertThat(result.resetEpochSeconds())
//                 .isBetween(beforeCall + window.getSeconds(), afterCall + window.getSeconds() + 1);
//     }

//     @Test
//     @DisplayName("요청 한도를 초과하면 차단된다")
//     void checkRateLimit_DeniesWhenLimitExceeded() {
//         int maxRequests = 5;
//         Duration window = Duration.ofSeconds(60);
//         // 고유 ID 사용 (다른 테스트와 겹치지 않게)
//         String clientId = generateUniqueClientId("ip:127.0.0.1");

//         // 한도까지 요청을 수행
//         for (int i = 0; i < maxRequests; i++) {
//             RateLimitCheckResult result =
//                     rateLimitService.checkRateLimit(clientId, maxRequests, window);
//             assertThat(result.allowed()).isTrue();
//         }

//         // 한도 초과 요청
//         long beforeCall = Instant.now().getEpochSecond();
//         RateLimitCheckResult result =
//                 rateLimitService.checkRateLimit(clientId, maxRequests, window);
//         long afterCall = Instant.now().getEpochSecond();

//         assertThat(result.allowed()).isFalse();
//         assertThat(result.limit()).isEqualTo(maxRequests);
//         assertThat(result.remaining()).isZero();

//         // 차단 시에는 대기 시간이 0보다 커야 함
//         assertThat(result.retryAfterSeconds()).isBetween(1L, window.getSeconds());

//         assertThat(result.resetEpochSeconds())
//                 .isBetween(beforeCall + result.retryAfterSeconds(), afterCall + result.retryAfterSeconds() + 1);
//     }

//     @Test
//     @DisplayName("연속 요청 시 카운트가 증가하고 남은 횟수가 감소한다")
//     void checkRateLimit_DecreasesRemainingOnConsecutiveRequests() {
//         int maxRequests = 3;
//         Duration window = Duration.ofSeconds(60);
//         String clientId = generateUniqueClientId("ip:192.168.1.1");

//         RateLimitCheckResult result1 =
//                 rateLimitService.checkRateLimit(clientId, maxRequests, window);
//         assertThat(result1.allowed()).isTrue();
//         assertThat(result1.remaining()).isEqualTo(2);

//         RateLimitCheckResult result2 =
//                 rateLimitService.checkRateLimit(clientId, maxRequests, window);
//         assertThat(result2.allowed()).isTrue();
//         assertThat(result2.remaining()).isEqualTo(1);

//         RateLimitCheckResult result3 =
//                 rateLimitService.checkRateLimit(clientId, maxRequests, window);
//         assertThat(result3.allowed()).isTrue();
//         assertThat(result3.remaining()).isZero();
//     }

//     @Test
//     @DisplayName("서로 다른 클라이언트는 독립적인 rate limit을 갖는다")
//     void checkRateLimit_IndependentLimitsPerClient() {
//         int maxRequests = 2;
//         Duration window = Duration.ofSeconds(60);
//         String clientId1 = generateUniqueClientId("ip:10.0.0.1");
//         String clientId2 = generateUniqueClientId("ip:10.0.0.2");

//         for (int i = 0; i < maxRequests; i++) {
//             RateLimitCheckResult result =
//                     rateLimitService.checkRateLimit(clientId1, maxRequests, window);
//             assertThat(result.allowed()).isTrue();
//         }

//         // 클라이언트 1은 차단됨
//         RateLimitCheckResult result1 =
//                 rateLimitService.checkRateLimit(clientId1, maxRequests, window);
//         assertThat(result1.allowed()).isFalse();

//         // 클라이언트 2는 여전히 허용됨
//         RateLimitCheckResult result2 =
//                 rateLimitService.checkRateLimit(clientId2, maxRequests, window);
//         assertThat(result2.allowed()).isTrue();
//         assertThat(result2.remaining()).isEqualTo(1);
//     }
// }