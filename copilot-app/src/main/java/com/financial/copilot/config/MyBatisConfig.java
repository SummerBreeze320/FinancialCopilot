package com.financial.copilot.config;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.financial.copilot.data.config.UuidTypeHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class MyBatisConfig {

    @Bean
    ConfigurationCustomizer uuidTypeHandlerCustomizer() {
        return configuration -> configuration.getTypeHandlerRegistry().register(UUID.class, new UuidTypeHandler());
    }
}
