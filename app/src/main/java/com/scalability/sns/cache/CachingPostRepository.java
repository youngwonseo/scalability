package com.scalability.sns.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.scalability.sns.post.Post;
import com.scalability.sns.post.PostRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache-aside decorator (S4).
 *
 * <ul>
 *   <li>{@code post:{id}}: single post, evicted when the post is liked</li>
 *   <li>{@code timeline:{userId}}: first page of {@value #TIMELINE_PAGE} posts only, evicted when the user
 *       posts. Like counts inside a cached timeline may be stale for up to the timeline TTL.</li>
 * </ul>
 *
 * Writes go to storage first, then evict, so a reader can at worst re-cache the new value.
 * Missing posts are not cached.
 */
public class CachingPostRepository implements PostRepository {

    static final int TIMELINE_PAGE = 20;

    private static final TypeReference<List<Post>> POST_LIST = new TypeReference<>() {
    };

    private final PostRepository storage;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration postTtl;
    private final Duration timelineTtl;
    private final Counter postHits;
    private final Counter postMisses;
    private final Counter timelineHits;
    private final Counter timelineMisses;

    public CachingPostRepository(PostRepository storage, StringRedisTemplate redis, JsonMapper json,
            Duration postTtl, Duration timelineTtl, MeterRegistry meters) {
        this.storage = storage;
        this.redis = redis;
        this.json = json;
        this.postTtl = postTtl;
        this.timelineTtl = timelineTtl;
        this.postHits = counter(meters, "post", "hit");
        this.postMisses = counter(meters, "post", "miss");
        this.timelineHits = counter(meters, "timeline", "hit");
        this.timelineMisses = counter(meters, "timeline", "miss");
    }

    @Override
    public Optional<Post> findById(long id) {
        String key = postKey(id);
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            postHits.increment();
            return Optional.of(json.readValue(cached, Post.class));
        }
        postMisses.increment();
        Optional<Post> post = storage.findById(id);
        post.ifPresent(p -> redis.opsForValue().set(key, json.writeValueAsString(p), postTtl));
        return post;
    }

    @Override
    public List<Post> findByUserId(long userId, Long beforeId, int limit) {
        if (beforeId != null || limit != TIMELINE_PAGE) {
            return storage.findByUserId(userId, beforeId, limit);
        }
        return cached(timelineKey(userId), timelineTtl, timelineHits, timelineMisses,
                () -> storage.findByUserId(userId, null, TIMELINE_PAGE));
    }

    @Override
    public void save(Post post) {
        storage.save(post);
        redis.delete(timelineKey(post.userId()));
    }

    @Override
    public boolean incrementLikeCount(long id) {
        boolean updated = storage.incrementLikeCount(id);
        if (updated) {
            redis.delete(postKey(id));
        }
        return updated;
    }

    private List<Post> cached(String key, Duration ttl, Counter hits, Counter misses, Supplier<List<Post>> loader) {
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            hits.increment();
            return json.readValue(cached, POST_LIST);
        }
        misses.increment();
        List<Post> posts = loader.get();
        redis.opsForValue().set(key, json.writeValueAsString(posts), ttl);
        return posts;
    }

    private static String postKey(long id) {
        return "post:" + id;
    }

    private static String timelineKey(long userId) {
        return "timeline:" + userId;
    }

    private static Counter counter(MeterRegistry meters, String cache, String result) {
        return Counter.builder("sns.cache.requests").tag("cache", cache).tag("result", result).register(meters);
    }
}
