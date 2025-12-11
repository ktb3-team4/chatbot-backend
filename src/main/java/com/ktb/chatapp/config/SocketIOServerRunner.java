package com.ktb.chatapp.config;

import com.corundumstudio.socketio.SocketIOServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

@Component
@RequiredArgsConstructor
@Slf4j
public class SocketIOServerRunner implements CommandLineRunner {

    private final SocketIOServer server;

    @Override
    public void run(String... args) {
        log.info("✨ Socket.IO 서버 시동 거는 중...");
        try {
            server.start();
            log.info("🚀 Socket.IO Server started at port: {}", server.getConfiguration().getPort());
        } catch (Exception e) {
            log.error("💥 Socket.IO 서버 시작 실패!", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.stop();
            log.info("🛑 Socket.IO Server stopped.");
        }
    }
}