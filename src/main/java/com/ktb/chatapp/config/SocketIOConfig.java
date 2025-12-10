package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.LocalChatDataStore;
import com.ktb.chatapp.websocket.socketio.store.RedissonStoreFactory; // 새로 만든 클래스 임포트
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;

import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIOConfig {

    @Value("${socketio.server.host:0.0.0.0}")
    private String host;

    @Value("${socketio.server.port:5002}")
    private Integer port;

    // RedissonClient 주입 추가
    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIOServer(AuthTokenListener authTokenListener, RedissonClient redissonClient) {
        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
        config.setHostname(host);
        config.setPort(port);

        int cores = Runtime.getRuntime().availableProcessors();
        config.setBossThreads(1);
        config.setWorkerThreads(Math.max(4, cores * 2));

        var socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        socketConfig.setTcpNoDelay(true);

        config.setSocketConfig(socketConfig);
        config.setOrigin("*");

        config.setPingTimeout(60000);
        config.setPingInterval(25000);
        config.setUpgradeTimeout(10000);

        config.setMaxFramePayloadLength(1024 * 1024);
        config.setMaxHttpContentLength(1024 * 1024);
        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule(), new BlackbirdModule()));

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));

        // 중요: MemoryStoreFactory 대신 RedissonStoreFactory 사용
        config.setStoreFactory(new RedissonStoreFactory(redissonClient));
        log.info("Socket.IO StoreFactory set to RedissonStoreFactory (Redis)");

        log.info("Socket.IO server configured on {}:{} with {} boss, {} worker threads",
                host, port, config.getBossThreads(), config.getWorkerThreads());

        var socketIOServer = new SocketIOServer(config);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addAuthTokenListener(authTokenListener);

        return socketIOServer;
    }

    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(@Lazy SocketIOServer socketIOServer) {
        return new SpringAnnotationScanner(socketIOServer);
    }

    /**
     * TODO: 완전한 분산 환경을 위해서는 이 ChatDataStore(참여자 목록 등)도
     * Redis 기반(Redisson Map 등)으로 교체해야 합니다.
     * 현재는 소켓 연결 레이어만 Redis로 교체되었습니다.
     */
    @Bean
    @ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
    public ChatDataStore chatDataStore() {
        return new LocalChatDataStore();
    }
}