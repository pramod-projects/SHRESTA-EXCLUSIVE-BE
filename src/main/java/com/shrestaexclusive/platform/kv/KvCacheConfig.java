package com.shrestaexclusive.platform.kv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableConfigurationProperties(KvCacheProperties.class)
public class KvCacheConfig {

    @Bean
    RedisTemplate<String, String> shrestaKvRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    KvReadThroughCache kvReadThroughCache(
            RedisTemplate<String, String> shrestaKvRedisTemplate,
            ObjectMapper objectMapper,
            KvCacheProperties properties
    ) {
        return new RedisKvReadThroughCache(shrestaKvRedisTemplate, objectMapper, properties);
    }
}
