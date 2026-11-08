package com.scalability.sns.loader;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the loader instead of serving traffic:
 * {@code java -jar sns.jar --spring.profiles.active=loader --sns.loader.users=1000000 --sns.loader.posts=10000000}
 */
@Component
@Profile("loader")
class DataLoaderRunner implements ApplicationRunner {

    private final DataLoader loader;
    private final long users;
    private final long posts;

    DataLoaderRunner(
            DataSource dataSource,
            @Value("${sns.loader.users}") long users,
            @Value("${sns.loader.posts}") long posts,
            @Value("${sns.loader.threads}") int threads,
            @Value("${sns.loader.batch-size}") int batchSize) {
        this.loader = new DataLoader(dataSource, threads, batchSize);
        this.users = users;
        this.posts = posts;
    }

    @Override
    public void run(ApplicationArguments args) {
        loader.load(users, posts);
    }
}
