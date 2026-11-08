package com.scalability.sns.loader;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.scalability.sns.storage.ConditionalOnMySqlStorage;

/**
 * Runs the loader instead of serving traffic:
 * {@code java -jar sns.jar --spring.profiles.active=loader --sns.loader.users=1000000 --sns.loader.posts=10000000}
 */
@Component
@Profile("loader")
@ConditionalOnMySqlStorage
class DataLoaderRunner implements ApplicationRunner {

    private final DataLoader loader;
    private final long users;
    private final long posts;
    private final ApplicationContext context;

    DataLoaderRunner(
            ApplicationContext context,
            DataSource dataSource,
            @Value("${sns.loader.users}") long users,
            @Value("${sns.loader.posts}") long posts,
            @Value("${sns.loader.threads}") int threads,
            @Value("${sns.loader.batch-size}") int batchSize) {
        this.loader = new DataLoader(dataSource, threads, batchSize);
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
