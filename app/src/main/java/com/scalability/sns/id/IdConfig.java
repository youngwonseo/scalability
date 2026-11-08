package com.scalability.sns.id;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class IdConfig {

    /** Each app instance needs a distinct worker id (1..31), set via WORKER_ID. */
    @Bean
    SnowflakeIdGenerator snowflakeIdGenerator(@Value("${sns.worker-id}") int workerId) {
        return new SnowflakeIdGenerator(workerId);
    }
}
