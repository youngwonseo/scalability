package com.scalability.sns.shard;

import java.util.List;
import java.util.Optional;

import com.scalability.sns.post.Post;
import com.scalability.sns.post.PostRepository;

/**
 * S6: one MySQL repository per physical shard. Every API operation touches exactly one shard
 * (a user's posts live with the user; a post id carries its shard), so there are no cross-shard queries.
 */
public class ShardedPostRepository implements PostRepository {

    private final ShardRouter router;
    private final List<PostRepository> shards;

    public ShardedPostRepository(ShardRouter router, List<? extends PostRepository> shards) {
        if (shards.size() != router.shardCount()) {
            throw new IllegalArgumentException("expected " + router.shardCount() + " shards, got " + shards.size());
        }
        this.router = router;
        this.shards = List.copyOf(shards);
    }

    @Override
    public Optional<Post> findById(long id) {
        return shards.get(router.shardOfPost(id)).findById(id);
    }

    @Override
    public List<Post> findByUserId(long userId, Long beforeId, int limit) {
        return shards.get(router.shardOfUser(userId)).findByUserId(userId, beforeId, limit);
    }

    @Override
    public void save(Post post) {
        shards.get(router.shardOfUser(post.userId())).save(post);
    }

    @Override
    public boolean incrementLikeCount(long id) {
        return shards.get(router.shardOfPost(id)).incrementLikeCount(id);
    }
}
