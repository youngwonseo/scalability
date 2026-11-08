package com.scalability.sns.cassandra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.datastax.oss.driver.api.core.CqlSession;

/** {@code --spring.profiles.active=cassandra,loader} */
@Component
@Profile("loader")
@ConditionalOnProperty(name = "sns.storage", havingValue = "cassandra")
class CassandraDataLoaderRunner implements ApplicationRunner {

    private final CassandraDataLoader loader;
    private final long users;
    private final long posts;
    private final ApplicationContext context;

    CassandraDataLoaderRunner(
            ApplicationContext context,
            CqlSession session,
            @Value("${sns.cassandra.keyspace}") String keyspace,
            @Value("${sns.cassandra.local-datacenter}") String datacenter,
            @Value("${sns.cassandra.replication-factor}") int replicationFactor,
            @Value("${sns.loader.users}") long users,
            @Value("${sns.loader.posts}") long posts,
            @Value("${sns.loader.cassandra-in-flight:512}") int inFlight) {
        this.loader = new CassandraDataLoader(session, keyspace, datacenter, replicationFactor, inFlight);
        this.users = users;
        this.posts = posts;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        loader.load(users, posts);
        // exit even if client libraries (e.g. the Cassandra driver) keep non-daemon threads alive
        System.exit(SpringApplication.exit(context));
    }
}
