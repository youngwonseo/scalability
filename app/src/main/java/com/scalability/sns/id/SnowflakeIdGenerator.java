package com.scalability.sns.id;

import java.util.function.LongSupplier;

/**
 * 64-bit ID: [41 timestamp ms][10 logical shard][5 worker][7 sequence].
 *
 * <p>The logical shard is derived from the owning user ({@code userId % 1024}), so any service can
 * find a post's shard from its ID alone. Physical shards (S6) map to logical shards with
 * {@code logicalShard % physicalShardCount}.
 *
 * <p>Seeded rows created by the data loader use a small sequence number {@code n} instead of a
 * timestamp and worker 0, which keeps them below 2^53 so the load generator (JavaScript) can compute
 * them exactly, and below any ID generated at runtime.
 */
public final class SnowflakeIdGenerator {

    /** 2026-01-01T00:00:00Z */
    public static final long EPOCH = 1_767_225_600_000L;

    public static final int LOGICAL_SHARDS = 1024;

    private static final int SEQUENCE_BITS = 7;
    private static final int WORKER_BITS = 5;
    private static final int SHARD_BITS = 10;

    private static final int WORKER_SHIFT = SEQUENCE_BITS;
    private static final int SHARD_SHIFT = SEQUENCE_BITS + WORKER_BITS;
    private static final int TIMESTAMP_SHIFT = SHARD_SHIFT + SHARD_BITS;

    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long SHARD_MASK = (1L << SHARD_BITS) - 1;
    private static final int MAX_WORKER = (1 << WORKER_BITS) - 1;

    private final long workerId;
    private final LongSupplier clock;

    private long lastTimestamp = -1;
    private long sequence;

    /** @param workerId 1..31, unique per app instance (0 is reserved for seeded data) */
    public SnowflakeIdGenerator(int workerId, LongSupplier clock) {
        if (workerId < 1 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("workerId must be between 1 and " + MAX_WORKER + ": " + workerId);
        }
        this.workerId = workerId;
        this.clock = clock;
    }

    public SnowflakeIdGenerator(int workerId) {
        this(workerId, System::currentTimeMillis);
    }

    public synchronized long nextId(long userId) {
        // never move backwards, even if the wall clock does
        long timestamp = Math.max(clock.getAsLong() - EPOCH, lastTimestamp);
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitForNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = timestamp;
        return compose(timestamp, logicalShard(userId), workerId, sequence);
    }

    private long waitForNextMillis(long last) {
        long timestamp;
        do {
            Thread.onSpinWait();
            timestamp = clock.getAsLong() - EPOCH;
        } while (timestamp <= last);
        return timestamp;
    }

    /** ID of the n-th seeded post (n >= 1). Must stay in sync with the k6 scripts. */
    public static long seededId(long n, long userId) {
        return compose(n, logicalShard(userId), 0, 0);
    }

    public static int logicalShard(long userId) {
        return (int) Math.floorMod(userId, LOGICAL_SHARDS);
    }

    public static int logicalShardOf(long id) {
        return (int) ((id >>> SHARD_SHIFT) & SHARD_MASK);
    }

    private static long compose(long timestamp, long shard, long worker, long sequence) {
        return (timestamp << TIMESTAMP_SHIFT) | (shard << SHARD_SHIFT) | (worker << WORKER_SHIFT) | sequence;
    }
}
