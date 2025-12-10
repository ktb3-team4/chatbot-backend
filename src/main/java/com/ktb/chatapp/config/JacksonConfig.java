package com.ktb.chatapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {
    @Bean
    public com.fasterxml.jackson.module.blackbird.BlackbirdModule blackbirdModule() {
        return new com.fasterxml.jackson.module.blackbird.BlackbirdModule();
    }
}
