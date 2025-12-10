package com.ktb.chatapp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RedissonClient redissonClient;

    public RateLimitCheckResult checkRateLimit(String clientId, int maxRequests, Duration window) {
        String key = "limiter:" + clientId;

        RRateLimiter limiter = redissonClient.getRateLimiter(key);

        limiter.trySetRate(RateType.OVERALL, maxRequests, window.getSeconds(), RateIntervalUnit.SECONDS);

        limiter.expire(window.multipliedBy(2));

        boolean allowed = limiter.tryAcquire(1);

        if (allowed) {
            long remaining = limiter.availablePermits();

            return RateLimitCheckResult.allowed(
                    maxRequests,
                    (int) remaining,
                    window.getSeconds(),
                    0,
                    0
            );
        } else {
            return RateLimitCheckResult.rejected(
                    maxRequests,
                    window.getSeconds(),
                    0,
                    1
            );
        }
    }
}