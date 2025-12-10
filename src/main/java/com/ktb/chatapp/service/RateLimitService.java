package com.ktb.chatapp.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static java.net.InetAddress.getLocalHost;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final Map<String, Bucket> localCache = new ConcurrentHashMap<>();

    @Value("${HOSTNAME:''}")
    private String hostName;

    @PostConstruct
    public void init() {
        if (!hostName.isEmpty()) {
            return;
        }
        hostName = generateHostname();
    }

    private String generateHostname() {
        try {
            return getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
    }

    public RateLimitCheckResult checkRateLimit(String _clientId, int maxRequests, Duration window) {
        String actualClientId = hostName + ":" + _clientId;
        long windowSeconds = Math.max(1L, window.getSeconds());
        long nowEpochSeconds = Instant.now().getEpochSecond();

        Bucket bucket = localCache.computeIfAbsent(actualClientId, k -> createNewBucket(maxRequests, window));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            long remainingTokens = probe.getRemainingTokens();

            long resetEpochSeconds = nowEpochSeconds + windowSeconds;

            return RateLimitCheckResult.allowed(
                    maxRequests,
                    (int) remainingTokens,
                    windowSeconds,
                    resetEpochSeconds,
                    0
            );
        } else {
            long nanosToWaitForRefill = probe.getNanosToWaitForRefill();
            long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(nanosToWaitForRefill);
            retryAfterSeconds = Math.max(1L, retryAfterSeconds);

            long resetEpochSeconds = nowEpochSeconds + retryAfterSeconds;

            return RateLimitCheckResult.rejected(
                    maxRequests,
                    windowSeconds,
                    resetEpochSeconds,
                    retryAfterSeconds
            );
        }
    }

    // 새 버킷 생성 메서드
    private Bucket createNewBucket(int maxRequests, Duration window) {
        Bandwidth limit = Bandwidth.classic(maxRequests, Refill.intervally(maxRequests, window));
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }
}