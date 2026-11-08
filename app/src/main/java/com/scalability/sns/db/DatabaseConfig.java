package com.scalability.sns.db;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.scalability.sns.storage.ConditionalOnMySqlStorage;
import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Two JdbcClients: {@code writeJdbcClient} always targets the primary (Boot's DataSource);
 * {@code readJdbcClient} round-robins over the replicas in {@code sns.replica.urls} (S3), or falls back
 * to the primary when none are configured (S1/S2).
 *
 * <p>Each replica gets its own Hikari pool of the same size as the primary's, so every DB node sees the
 * same connection budget (EXPERIMENT_PLAN.md 7).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnMySqlStorage
public class DatabaseConfig {

    @Bean
    @Primary
    JdbcClient writeJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    ReplicaPools replicaPools(
            @Value("${sns.replica.urls:}") String urls,
            @Value("${sns.db.params}") String params,
            @Value("${spring.datasource.hikari.maximum-pool-size}") int poolSize,
            @Value("${spring.datasource.hikari.connection-timeout}") long connectionTimeout,
            JdbcConnectionDetails primary,
            MeterRegistry meterRegistry) {
        var settings = new HikariPools.Settings(params, primary.getUsername(), primary.getPassword(), poolSize,
                connectionTimeout, meterRegistry);
        List<HikariDataSource> pools = HikariPools.create(urls, "replica", true, settings);
        return new ReplicaPools(pools);
    }

    @Bean
    @Qualifier("read")
    JdbcClient readJdbcClient(ReplicaPools replicas, DataSource primary) {
        return replicas.pools().isEmpty()
                ? JdbcClient.create(primary)
                : JdbcClient.create(new RoundRobinDataSource(replicas.pools()));
    }

    /** Not a DataSource bean on purpose: that would switch off Boot's primary DataSource auto-configuration. */
    public record ReplicaPools(List<HikariDataSource> pools) implements AutoCloseable {

        @Override
        public void close() {
            HikariPools.closeAll(pools);
        }
    }
}
