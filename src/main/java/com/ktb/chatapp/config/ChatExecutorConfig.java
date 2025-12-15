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
        ex.setCorePoolSize(cores * 4);
        ex.setMaxPoolSize(cores * 8);
        ex.setQueueCapacity(2_000);
        ex.setThreadNamePrefix("chat-worker-");
        ex.setAllowCoreThreadTimeOut(true);
        ex.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        ex.initialize();
        return ex;
    }

    @Bean("chatPersistenceExecutor")
    public ThreadPoolTaskExecutor chatPersistenceExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();

        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(cores * 4);
        ex.setMaxPoolSize(cores * 8);
        ex.setQueueCapacity(2_000);
        ex.setThreadNamePrefix("chat-persist-");
        ex.setAllowCoreThreadTimeOut(true);
        ex.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        ex.initialize();
        return ex;
    }
}
