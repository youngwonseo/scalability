package com.scalability.sns.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    private static final long NOW = SnowflakeIdGenerator.EPOCH + 1_000_000L;

    @Test
    void encodesTimestampShardAndWorker() {
        var generator = new SnowflakeIdGenerator(3, () -> NOW);

        long id = generator.nextId(1_025L); // 1025 % 1024 = 1

        assertThat(id >>> 22).isEqualTo(1_000_000L);
        assertThat(SnowflakeIdGenerator.logicalShardOf(id)).isEqualTo(1);
        assertThat((id >>> 7) & 0x1F).isEqualTo(3);
        assertThat(id & 0x7F).isZero();
    }

    @Test
    void idsAreUniqueAndIncreasingWithinSameMillisecond() {
        var clock = new AtomicLong(NOW);
        var generator = new SnowflakeIdGenerator(1, clock::get);

        long previous = -1;
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 128; i++) {
            long id = generator.nextId(42L);
            assertThat(id).isGreaterThan(previous);
            assertThat(seen.add(id)).isTrue();
            previous = id;
        }
    }

    @Test
    void waitsForNextMillisecondWhenSequenceIsExhausted() {
        var clock = new AtomicLong(NOW);
        var generator = new SnowflakeIdGenerator(1, clock::get);
        for (int i = 0; i < 128; i++) {
            generator.nextId(1L);
        }
        // the 129th id needs a new millisecond; advance the clock from another thread
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
            clock.incrementAndGet();
        });

        long id = generator.nextId(1L);

        assertThat(id >>> 22).isEqualTo(1_000_001L);
        assertThat(id & 0x7F).isZero();
    }

    @Test
    void neverGoesBackwardsWhenClockMovesBack() {
        var clock = new AtomicLong(NOW);
        var generator = new SnowflakeIdGenerator(1, clock::get);
        long first = generator.nextId(1L);

        clock.addAndGet(-5);
        long second = generator.nextId(1L);

        assertThat(second).isGreaterThan(first);
    }

    @Test
    void seededIdsAreSafeJavaScriptIntegersAndBelowRuntimeIds() {
        long seeded = SnowflakeIdGenerator.seededId(10_000_000L, 1_000_000L);
        long runtime = new SnowflakeIdGenerator(1, () -> SnowflakeIdGenerator.EPOCH + 60_000_000L).nextId(1L);

        assertThat(seeded).isLessThanOrEqualTo(9_007_199_254_740_991L); // Number.MAX_SAFE_INTEGER
        assertThat(seeded).isLessThan(runtime);
        assertThat(SnowflakeIdGenerator.logicalShardOf(seeded)).isEqualTo((int) (1_000_000L % 1024));
    }

    @Test
    void rejectsWorkerIdOutOfRange() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, () -> NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(32, () -> NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
