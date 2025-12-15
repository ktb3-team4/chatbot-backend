package com.ktb.chatapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ChatExecutorConfig {

    @Bean("chatWorkerExecutor")
    public ThreadPoolTaskExecutor chatWorkerExecutor() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int corePool = Math.max(2, cores * 2);
        int maxPool = Math.max(corePool, cores * 3);

        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(corePool);
        ex.setMaxPoolSize(maxPool);
        ex.setQueueCapacity(500);
        ex.setThreadNamePrefix("chat-worker-");
        ex.setAllowCoreThreadTimeOut(true);
        ex.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        ex.initialize();
        return ex;
    }

    @Bean("chatPersistenceExecutor")
    public ThreadPoolTaskExecutor chatPersistenceExecutor() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int corePool = Math.max(2, cores * 2);
        int maxPool = Math.max(corePool, cores * 3);

        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(corePool);
        ex.setMaxPoolSize(maxPool);
        ex.setQueueCapacity(500);
        ex.setThreadNamePrefix("chat-persist-");
        ex.setAllowCoreThreadTimeOut(true);
        ex.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        ex.initialize();
        return ex;
    }
}
