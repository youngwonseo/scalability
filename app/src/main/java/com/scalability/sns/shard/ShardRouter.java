package com.scalability.sns.shard;

import com.scalability.sns.id.SnowflakeIdGenerator;

/**
 * Maps the 1024 logical shards (userId % 1024, also encoded in every post id) onto N physical shards.
 * N must divide 1024 so every physical shard gets the same number of logical shards.
 */
public class ShardRouter {

    private final int shardCount;

    public ShardRouter(int shardCount) {
        if (shardCount < 1 || SnowflakeIdGenerator.LOGICAL_SHARDS % shardCount != 0) {
            throw new IllegalArgumentException(
                    "shard count must divide " + SnowflakeIdGenerator.LOGICAL_SHARDS + ": " + shardCount);
        }
        this.shardCount = shardCount;
    }

    public int shardCount() {
        return shardCount;
    }

    public int shardOfUser(long userId) {
        return SnowflakeIdGenerator.logicalShard(userId) % shardCount;
    }

    public int shardOfPost(long postId) {
        return SnowflakeIdGenerator.logicalShardOf(postId) % shardCount;
    }
}
