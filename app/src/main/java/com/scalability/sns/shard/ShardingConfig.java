package com.scalability.sns.shard;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.scalability.sns.db.HikariPools;
import com.scalability.sns.post.MySqlPostRepository;
import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.MeterRegistry;

/** Active with the {@code sharded} profile: one Hikari pool per shard in {@code sns.shard.urls}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sns.storage", havingValue = "sharded")
class ShardingConfig {

    @Bean
    ShardPools shardPools(
            @Value("${sns.shard.urls}") String urls,
            @Value("${sns.db.params}") String params,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.hikari.maximum-pool-size}") int poolSize,
            @Value("${spring.datasource.hikari.connection-timeout}") long connectionTimeout,
            MeterRegistry meters) {
        var settings = new HikariPools.Settings(params, username, password, poolSize, connectionTimeout, meters);
        List<HikariDataSource> pools = HikariPools.create(urls, "shard", false, settings);
        if (pools.isEmpty()) {
            throw new IllegalStateException("sns.shard.urls (DB_SHARD_URLS) is empty");
        }
        return new ShardPools(pools);
    }

    @Bean
    ShardRouter shardRouter(ShardPools pools) {
        return new ShardRouter(pools.pools().size());
    }

    @Bean
    @Qualifier("storage")
    ShardedPostRepository shardedPostRepository(ShardRouter router, ShardPools pools) {
        return new ShardedPostRepository(router, pools.pools().stream()
                .map(JdbcClient::create)
                .map(client -> new MySqlPostRepository(client, client))
                .toList());
    }

    /** Shard index i is pools().get(i). */
    record ShardPools(List<HikariDataSource> pools) implements AutoCloseable {

        @Override
        public void close() {
            HikariPools.closeAll(pools);
        }
    }
}
