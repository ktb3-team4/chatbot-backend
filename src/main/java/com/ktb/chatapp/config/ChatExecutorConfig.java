package com.ktb.chatapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ChatExecutorConfig {

    @Bean("chatWorkerExecutor")
    public ThreadPoolTaskExecutor chatWorkerExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();

        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(cores * 8);
        ex.setMaxPoolSize(cores * 16);
        ex.setQueueCapacity(20_000);
        ex.setThreadNamePrefix("chat-worker-");
        ex.setAllowCoreThreadTimeOut(true);
        ex.initialize();
        return ex;
    }

    @Bean("chatPersistenceExecutor")
    public ThreadPoolTaskExecutor chatPersistenceExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();

        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(cores * 6);
        ex.setMaxPoolSize(cores * 12);
        ex.setQueueCapacity(20_000);
        ex.setThreadNamePrefix("chat-persist-");
        ex.setAllowCoreThreadTimeOut(true);
        ex.initialize();
        return ex;
    }
}
