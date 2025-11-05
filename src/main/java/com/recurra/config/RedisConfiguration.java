package com.recurra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.ByteArrayRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis configuration for exact cache.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "recurra.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisConfiguration {

    /**
     * Configure Redis connection factory with timeouts and resilience.
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        // Socket options
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .keepAlive(true)
                .build();

        // Client options with timeouts
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(5)))
                .build();

        // Lettuce client configuration
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofSeconds(5))
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(clientConfig);

        log.info("Configured Redis connection factory with timeouts and resilience");
        return factory;
    }

    /**
     * Redis template for byte array storage (compressed cache entries).
     */
    @Bean
    public RedisTemplate<String, byte[]> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Use byte array serializer for values (we'll handle compression ourselves)
        template.setValueSerializer(new ByteArrayRedisSerializer());
        template.setHashValueSerializer(new ByteArrayRedisSerializer());

        template.afterPropertiesSet();

        log.info("Configured RedisTemplate for byte array storage");
        return template;
    }
}
