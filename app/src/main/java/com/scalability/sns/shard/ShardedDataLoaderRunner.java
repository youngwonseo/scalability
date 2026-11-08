package com.scalability.sns.shard;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.scalability.sns.loader.DataLoader;

/** {@code --spring.profiles.active=sharded,loader}: loads every shard in parallel with only its users' rows. */
@Component
@Profile("loader")
@ConditionalOnProperty(name = "sns.storage", havingValue = "sharded")
class ShardedDataLoaderRunner implements ApplicationRunner {

    private final ShardingConfig.ShardPools pools;
    private final ShardRouter router;
    private final ApplicationContext context;
    private final long users;
    private final long posts;
    private final int threads;
    private final int batchSize;

    ShardedDataLoaderRunner(
            ShardingConfig.ShardPools pools,
            ShardRouter router,
            ApplicationContext context,
            @Value("${sns.loader.users}") long users,
            @Value("${sns.loader.posts}") long posts,
            @Value("${sns.loader.threads}") int threads,
            @Value("${sns.loader.batch-size}") int batchSize) {
        this.pools = pools;
        this.router = router;
        this.context = context;
        this.users = users;
        this.posts = posts;
        this.threads = threads;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<CompletableFuture<Void>> loads = new ArrayList<>();
        for (int shard = 0; shard < router.shardCount(); shard++) {
            int s = shard;
            var loader = new DataLoader(pools.pools().get(s), threads, batchSize, userId -> router.shardOfUser(userId) == s);
            loads.add(CompletableFuture.runAsync(() -> loader.load(users, posts)));
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
        System.exit(SpringApplication.exit(context));
    }
}
