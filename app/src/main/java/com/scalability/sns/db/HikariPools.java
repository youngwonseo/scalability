package com.scalability.sns.db;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;

import io.micrometer.core.instrument.MeterRegistry;

/** Builds the extra Hikari pools for read replicas (S3) and shards (S6), with Micrometer metrics. */
public final class HikariPools {

    private HikariPools() {
    }

    public record Settings(String params, String username, String password, int poolSize, long connectionTimeout,
            MeterRegistry meters) {
    }

    /** One pool per comma-separated JDBC URL, named {@code <prefix>-1..N}. */
    public static List<HikariDataSource> create(String urls, String prefix, boolean readOnly, Settings settings) {
        List<String> list = Arrays.stream(urls.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<HikariDataSource> pools = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String url = list.get(i);
            var pool = new HikariDataSource();
            pool.setPoolName(prefix + "-" + (i + 1));
            pool.setJdbcUrl(url.contains("?") ? url : url + "?" + settings.params());
            pool.setUsername(settings.username());
            pool.setPassword(settings.password());
            pool.setMaximumPoolSize(settings.poolSize());
            pool.setMinimumIdle(settings.poolSize());
            pool.setConnectionTimeout(settings.connectionTimeout());
            pool.setReadOnly(readOnly);
            pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(settings.meters()));
            pools.add(pool);
        }
        return pools;
    }

    public static void closeAll(List<HikariDataSource> pools) {
        pools.forEach(HikariDataSource::close);
    }
}
