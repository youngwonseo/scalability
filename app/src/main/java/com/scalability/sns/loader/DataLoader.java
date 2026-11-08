package com.scalability.sns.loader;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.scalability.sns.id.SnowflakeIdGenerator;

/**
 * Loads a deterministic dataset so load tests can compute valid IDs without querying the DB.
 *
 * <ul>
 *   <li>users: id 1..userCount</li>
 *   <li>posts: n = 1..postCount, owner = ((n - 1) % userCount) + 1,
 *       id = {@link SnowflakeIdGenerator#seededId(long, long) seededId(n, owner)}</li>
 * </ul>
 *
 * Every user ends up with postCount / userCount posts, spread evenly over all logical shards.
 */
public class DataLoader {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    private static final int CHUNK_SIZE = 100_000;
    private static final int CONTENT_VARIANTS = 1_000;

    private final DataSource dataSource;
    private final int threads;
    private final int batchSize;
    /** S6: only rows of users accepted by this filter are written (one physical shard). */
    private final LongPredicate userFilter;
    private static final String[] CONTENTS = generateContents();

    public DataLoader(DataSource dataSource, int threads, int batchSize) {
        this(dataSource, threads, batchSize, userId -> true);
    }

    public DataLoader(DataSource dataSource, int threads, int batchSize, LongPredicate userFilter) {
        this.dataSource = dataSource;
        this.threads = threads;
        this.batchSize = batchSize;
        this.userFilter = userFilter;
    }

    public void load(long userCount, long postCount) {
        long started = System.nanoTime();

        log.info("Recreating schema");
        new ResourceDatabasePopulator(new ClassPathResource("db/mysql/schema.sql")).execute(dataSource);

        loadTable("users", userCount, userFilter, "INSERT INTO users (id, name, created_at) VALUES (?, ?, ?)", (ps, n) -> {
            ps.setLong(1, n);
            ps.setString(2, "user" + n);
            ps.setTimestamp(3, new Timestamp(SnowflakeIdGenerator.EPOCH));
        });

        loadTable("posts", postCount, n -> userFilter.test(ownerOf(n, userCount)),
                "INSERT INTO posts (id, user_id, content, like_count, created_at) VALUES (?, ?, ?, 0, ?)",
                (ps, n) -> {
                    long userId = ownerOf(n, userCount);
                    ps.setLong(1, SnowflakeIdGenerator.seededId(n, userId));
                    ps.setLong(2, userId);
                    ps.setString(3, contentFor(n));
                    // seeded "timestamp" is n ms after the epoch, consistent with the id
                    ps.setTimestamp(4, new Timestamp(SnowflakeIdGenerator.EPOCH + n));
                });

        log.info("Creating secondary indexes");
        new ResourceDatabasePopulator(new ClassPathResource("db/mysql/indexes.sql")).execute(dataSource);

        log.info("Done in {} s", (System.nanoTime() - started) / 1_000_000_000);
    }

    /** Hash n so posts of the same user (n, n + userCount, ...) do not all get the same content. */
    public static String contentFor(long n) {
        return CONTENTS[(int) Math.floorMod((n * 0x9E3779B97F4A7C15L) >>> 32, (long) CONTENT_VARIANTS)];
    }

    public static long ownerOf(long n, long userCount) {
        return ((n - 1) % userCount) + 1;
    }

    private void loadTable(String table, long total, LongPredicate include, String sql, RowBinder binder) {
        log.info("Loading {} {} rows with {} threads", total, table, threads);
        long started = System.nanoTime();
        var loaded = new AtomicLong();

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (long from = 1; from <= total; from += CHUNK_SIZE) {
                long start = from;
                long end = Math.min(from + CHUNK_SIZE - 1, total);
                futures.add(executor.submit(() -> {
                    insertRange(sql, include, binder, start, end);
                    long done = loaded.addAndGet(end - start + 1);
                    if (done % 1_000_000 < CHUNK_SIZE || done == total) {
                        double seconds = (System.nanoTime() - started) / 1e9;
                        log.info("{}: {}/{} rows ({} rows/s)", table, done, total, Math.round(done / seconds));
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading " + table, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to load " + table, e.getCause());
        }
    }

    private void insertRange(String sql, LongPredicate include, RowBinder binder, long start, long end)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            int pending = 0;
            for (long n = start; n <= end; n++) {
                if (!include.test(n)) {
                    continue;
                }
                binder.bind(ps, n);
                ps.addBatch();
                if (++pending == batchSize) {
                    ps.executeBatch();
                    connection.commit();
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
                connection.commit();
            }
        }
    }

    private static String[] generateContents() {
        var random = new SplittableRandom(42);
        String[] words = {"scalability", "mysql", "replica", "shard", "cache", "latency", "throughput", "spring",
                "java", "cassandra", "index", "query", "traffic", "server", "load", "test", "hello", "world"};
        String[] result = new String[CONTENT_VARIANTS];
        for (int i = 0; i < CONTENT_VARIANTS; i++) {
            int length = random.nextInt(80, 200);
            var sb = new StringBuilder(length + 16);
            while (sb.length() < length) {
                sb.append(words[random.nextInt(words.length)]).append(' ');
            }
            result[i] = sb.toString().trim();
        }
        return result;
    }

    @FunctionalInterface
    private interface RowBinder {
        void bind(PreparedStatement ps, long n) throws SQLException;
    }
}
