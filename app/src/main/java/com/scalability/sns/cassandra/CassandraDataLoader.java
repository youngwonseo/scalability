package com.scalability.sns.cassandra;

import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.scalability.sns.id.SnowflakeIdGenerator;
import com.scalability.sns.loader.DataLoader;

/**
 * Same deterministic dataset as {@link DataLoader} (ids computable by k6), written to both query tables.
 * Like counts start at 0, which needs no counter rows.
 */
public class CassandraDataLoader {

    private static final Logger log = LoggerFactory.getLogger(CassandraDataLoader.class);

    private final CqlSession session;
    private final String keyspace;
    private final String datacenter;
    private final int replicationFactor;
    private final int maxInFlight;

    public CassandraDataLoader(CqlSession session, String keyspace, String datacenter, int replicationFactor,
            int maxInFlight) {
        this.session = session;
        this.keyspace = keyspace;
        this.datacenter = datacenter;
        this.replicationFactor = replicationFactor;
        this.maxInFlight = maxInFlight;
    }

    public void load(long userCount, long postCount) {
        long started = System.nanoTime();
        log.info("Recreating keyspace {} (RF {})", keyspace, replicationFactor);
        CassandraSchema.recreate(session, keyspace, datacenter, replicationFactor);

        PreparedStatement byId = session.prepare("INSERT INTO " + keyspace
                + ".posts_by_id (id, user_id, content, created_at) VALUES (?, ?, ?, ?)");
        PreparedStatement byUser = session.prepare("INSERT INTO " + keyspace
                + ".posts_by_user (id, user_id, content, created_at) VALUES (?, ?, ?, ?)");

        var permits = new Semaphore(maxInFlight);
        var done = new AtomicLong();
        var failure = new AtomicReference<Throwable>();
        log.info("Loading {} posts ({} in flight)", postCount, maxInFlight);
        for (long n = 1; n <= postCount && failure.get() == null; n++) {
            long userId = DataLoader.ownerOf(n, userCount);
            long id = SnowflakeIdGenerator.seededId(n, userId);
            String content = DataLoader.contentFor(n);
            Instant createdAt = Instant.ofEpochMilli(SnowflakeIdGenerator.EPOCH + n);
            for (PreparedStatement statement : new PreparedStatement[] {byId, byUser}) {
                permits.acquireUninterruptibly();
                session.executeAsync(statement.bind(id, userId, content, createdAt)).whenComplete((rs, error) -> {
                    permits.release();
                    if (error != null) {
                        failure.compareAndSet(null, error);
                    }
                });
            }
            if (done.incrementAndGet() % 1_000_000 == 0) {
                log.info("posts: {}/{} ({} rows/s)", done.get(), postCount,
                        Math.round(done.get() / ((System.nanoTime() - started) / 1e9)));
            }
        }
        permits.acquireUninterruptibly(maxInFlight);
        if (failure.get() != null) {
            throw new IllegalStateException("Loading failed", failure.get());
        }
        log.info("Done: {} posts in {} s", postCount, (System.nanoTime() - started) / 1_000_000_000);
    }
}
