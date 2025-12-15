package com.ktb.chatapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserCacheService {

    private final CacheManager cacheManager;

    public void evictUserCaches(String userId, String email) {
        if (cacheManager == null) return;
        if (userId != null) {
            var userCache = cacheManager.getCache("user");
            if (userCache != null) {
                userCache.evict(userId);
            }
        }
        if (email != null) {
            var securityCache = cacheManager.getCache("security_user");
            if (securityCache != null) {
                securityCache.evict(email.toLowerCase());
            }
        }
    }
}

