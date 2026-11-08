package com.scalability.sns.cassandra;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;

/** Active with the {@code cassandra} profile ({@code sns.storage=cassandra}). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sns.storage", havingValue = "cassandra")
class CassandraConfig {

    @Bean(destroyMethod = "close")
    CqlSession cqlSession(
            @Value("${sns.cassandra.contact-points}") String contactPoints,
            @Value("${sns.cassandra.local-datacenter}") String datacenter,
            @Value("${sns.cassandra.keyspace}") String keyspace,
            @Value("${sns.cassandra.consistency}") String consistency,
            @Value("${sns.cassandra.request-timeout}") Duration requestTimeout,
            @Value("${sns.cassandra.use-keyspace:true}") boolean useKeyspace) {
        var config = DriverConfigLoader.programmaticBuilder()
                .withString(DefaultDriverOption.REQUEST_CONSISTENCY, consistency)
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, requestTimeout)
                .withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, Duration.ofSeconds(10))
                // schema changes during loading take longer than the 2 s default
                .withDuration(DefaultDriverOption.CONTROL_CONNECTION_AGREEMENT_TIMEOUT, Duration.ofSeconds(30))
                .build();
        var builder = CqlSession.builder()
                .addContactPoints(parse(contactPoints))
                .withLocalDatacenter(datacenter)
                .withConfigLoader(config);
        if (useKeyspace) {
            builder.withKeyspace(keyspace);
        }
        return builder.build();
    }

    @Bean
    @Qualifier("storage")
    CassandraPostRepository cassandraPostRepository(CqlSession session) {
        return new CassandraPostRepository(session);
    }

    static List<InetSocketAddress> parse(String contactPoints) {
        return Arrays.stream(contactPoints.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(hostPort -> {
                    int colon = hostPort.lastIndexOf(':');
                    return colon < 0
                            ? new InetSocketAddress(hostPort, 9042)
                            : new InetSocketAddress(hostPort.substring(0, colon),
                                    Integer.parseInt(hostPort.substring(colon + 1)));
                })
                .toList();
    }
}
