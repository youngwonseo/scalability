package com.scalability.sns.post;

import java.util.List;
import java.util.Optional;

/**
 * Storage boundary. Each scenario (single MySQL, read replicas, cache, Cassandra, sharding) provides
 * its own implementation; the API and load tests stay the same.
 */
public interface PostRepository {

    Optional<Post> findById(long id);

    /** Newest first. {@code beforeId} is an exclusive cursor; null starts from the newest post. */
    List<Post> findByUserId(long userId, Long beforeId, int limit);

    void save(Post post);

    /** @return false if the post does not exist */
    boolean incrementLikeCount(long id);
}
