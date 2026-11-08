package com.scalability.sns.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.scalability.sns.id.SnowflakeIdGenerator;

class ShardRouterTest {

    @Test
    void userAndTheirPostsLandOnTheSameShard() {
        var router = new ShardRouter(4);
        var generator = new SnowflakeIdGenerator(1);
        for (long userId = 1; userId <= 5_000; userId += 37) {
            long postId = generator.nextId(userId);
            assertThat(router.shardOfPost(postId)).isEqualTo(router.shardOfUser(userId));
            assertThat(router.shardOfPost(SnowflakeIdGenerator.seededId(userId, userId)))
                    .isEqualTo(router.shardOfUser(userId));
        }
    }

    @Test
    void spreadsUsersEvenly() {
        for (int shards : new int[] {2, 4}) {
            var router = new ShardRouter(shards);
            Map<Integer, Integer> counts = new HashMap<>();
            for (long userId = 1; userId <= 100_000; userId++) {
                counts.merge(router.shardOfUser(userId), 1, Integer::sum);
            }
            assertThat(counts).hasSize(shards);
            counts.values().forEach(c -> assertThat(c).isEqualTo(100_000 / shards));
        }
    }

    @Test
    void shardCountMustDivideLogicalShards() {
        assertThatThrownBy(() -> new ShardRouter(3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShardRouter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
