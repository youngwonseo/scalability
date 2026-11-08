package com.scalability.sns.db;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;

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
        List<HikariDataSource> pools = new ArrayList<>();
        List<String> replicaUrls = Arrays.stream(urls.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (int i = 0; i < replicaUrls.size(); i++) {
            String url = replicaUrls.get(i);
            var pool = new HikariDataSource();
            pool.setPoolName("replica-" + (i + 1));
            pool.setJdbcUrl(url.contains("?") ? url : url + "?" + params);
            pool.setUsername(primary.getUsername());
            pool.setPassword(primary.getPassword());
            pool.setMaximumPoolSize(poolSize);
            pool.setMinimumIdle(poolSize);
            pool.setConnectionTimeout(connectionTimeout);
            pool.setReadOnly(true);
            pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
            pools.add(pool);
        }
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
            pools.forEach(HikariDataSource::close);
        }
    }
}
