package com.scalability.sns.cache;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.scalability.sns.post.PostRepository;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("sns.cache.enabled")
class CacheConfig {

    @Bean
    @Primary
    CachingPostRepository cachingPostRepository(
            @Qualifier("storage") PostRepository storage,
            StringRedisTemplate redis,
            JsonMapper json,
            @Value("${sns.cache.post-ttl}") Duration postTtl,
            @Value("${sns.cache.timeline-ttl}") Duration timelineTtl,
            MeterRegistry meters) {
        return new CachingPostRepository(storage, redis, json, postTtl, timelineTtl, meters);
    }
}
