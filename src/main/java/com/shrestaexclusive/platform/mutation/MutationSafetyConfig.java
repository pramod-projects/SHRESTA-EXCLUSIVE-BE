package com.shrestaexclusive.platform.mutation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shrestaexclusive.platform.kv.KvCacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
@EnableConfigurationProperties(MutationSafetyProperties.class)
public class MutationSafetyConfig {

    @Bean
    IdempotentMutationCoordinator idempotentMutationCoordinator(
            RedisTemplate<String, String> shrestaKvRedisTemplate,
            ObjectMapper objectMapper,
            KvCacheProperties kvCacheProperties,
            MutationSafetyProperties mutationSafetyProperties
    ) {
        return new RedisIdempotentMutationCoordinator(
                shrestaKvRedisTemplate,
                objectMapper,
                kvCacheProperties,
                mutationSafetyProperties
        );
    }
}
