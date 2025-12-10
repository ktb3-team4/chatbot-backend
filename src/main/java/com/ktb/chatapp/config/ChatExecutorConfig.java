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
        ex.setCorePoolSize(cores * 2);
        ex.setMaxPoolSize(cores * 4);
        ex.setQueueCapacity(10_000);
        ex.setThreadNamePrefix("chat-worker-");
        ex.setAllowCoreThreadTimeOut(true);
        ex.initialize();
        return ex;
    }
}

