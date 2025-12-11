package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.MemoryStoreFactory; // ✅ 메모리 스토어 사용
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import lombok.extern.slf4j.Slf4j;
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

    @Value("${socketio.server.host}")
    private String host;

    @Value("${socketio.server.port}")
    private Integer port;

    @Bean
    public SocketIOServer socketIOServer(AuthTokenListener authTokenListener) {
        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
        config.setHostname(host);
        config.setPort(port);

        // ⚠️ [로컬 개발용 안전 설정]
        // 1. Epoll 끄기 (Mac 호환성 문제 방지)
        // config.setUseLinuxNativeEpoll(true);

        // 2. Redis 끄기 -> 메모리 스토어 사용 (연결 문제 방지)
        config.setStoreFactory(new MemoryStoreFactory());

        // 3. 압축은 끄는 게 성능상 유리
        config.setHttpCompression(false);
        config.setWebsocketCompression(false);

        // 기본 설정
        config.setOrigin("*");
        config.setBossThreads(1);
        config.setWorkerThreads(4);

        config.setPingTimeout(60000);
        config.setPingInterval(25000);
        config.setUpgradeTimeout(10000);
        config.setMaxFramePayloadLength(1024 * 1024);
        config.setMaxHttpContentLength(1024 * 1024);
        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule(), new BlackbirdModule()));

        SocketIOServer server = new SocketIOServer(config);

        // 네임스페이스 등록
        server.getNamespace(com.corundumstudio.socketio.namespace.Namespace.DEFAULT_NAME)
                .addAuthTokenListener(authTokenListener);

        return server;
    }

    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(@Lazy SocketIOServer socketIOServer) {
        return new SpringAnnotationScanner(socketIOServer);
    }
}