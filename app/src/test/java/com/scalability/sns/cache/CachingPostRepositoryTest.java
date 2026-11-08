package com.scalability.sns.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.scalability.sns.TestcontainersConfiguration;
import com.scalability.sns.id.SnowflakeIdGenerator;
import com.scalability.sns.post.Post;
import com.scalability.sns.post.PostRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Proves caching by changing rows behind the cache's back: a cached read keeps returning the old
 * value until a write through the repository evicts it.
 */
@Testcontainers
@SpringBootTest(properties = {
        "sns.cache.enabled=true",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/mysql/schema.sql,classpath:db/mysql/indexes.sql"
})
@Import(TestcontainersConfiguration.class)
class CachingPostRepositoryTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    @Autowired
    PostRepository repository;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM posts").update();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void usesCachingRepository() {
        assertThat(repository).isInstanceOf(CachingPostRepository.class);
    }

    @Test
    void postReadsAreCachedUntilLiked() {
        long id = SnowflakeIdGenerator.seededId(1, 1);
        insertRow(id, 1, "original");

        assertThat(repository.findById(id)).get().extracting(Post::content).isEqualTo("original");
        jdbc.sql("UPDATE posts SET content = 'changed' WHERE id = ?").param(id).update();

        // served from cache
        assertThat(repository.findById(id)).get().extracting(Post::content).isEqualTo("original");

        // a write through the repository evicts the entry
        assertThat(repository.incrementLikeCount(id)).isTrue();
        Post fresh = repository.findById(id).orElseThrow();
        assertThat(fresh.content()).isEqualTo("changed");
        assertThat(fresh.likeCount()).isEqualTo(1);
    }

    @Test
    void cachedPostRoundTripsAllFields() {
        long id = SnowflakeIdGenerator.seededId(2, 5);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.save(new Post(id, 5, "hello | world", 3, createdAt));

        Post fromDb = repository.findById(id).orElseThrow();
        Post fromCache = repository.findById(id).orElseThrow();

        assertThat(fromCache).isEqualTo(fromDb).isEqualTo(new Post(id, 5, "hello | world", 3, createdAt));
    }

    @Test
    void firstTimelinePageIsCachedUntilUserPosts() {
        insertRow(SnowflakeIdGenerator.seededId(1, 7), 7, "a");

        assertThat(repository.findByUserId(7, null, 20)).hasSize(1);
        insertRow(SnowflakeIdGenerator.seededId(2, 7), 7, "b");
        assertThat(repository.findByUserId(7, null, 20)).hasSize(1); // cached

        repository.save(new Post(SnowflakeIdGenerator.seededId(3, 7), 7, "c", 0, Instant.now()));
        assertThat(repository.findByUserId(7, null, 20)).hasSize(3);
    }

    @Test
    void otherPagesBypassTheCache() {
        long first = SnowflakeIdGenerator.seededId(1, 9);
        insertRow(first, 9, "a");
        long before = SnowflakeIdGenerator.seededId(100, 9);

        assertThat(repository.findByUserId(9, before, 20)).hasSize(1);
        insertRow(SnowflakeIdGenerator.seededId(2, 9), 9, "b");
        assertThat(repository.findByUserId(9, before, 20)).hasSize(2);
        assertThat(repository.findByUserId(9, null, 10)).hasSize(2);
    }

    @Test
    void countsHitsAndMisses() {
        long id = SnowflakeIdGenerator.seededId(1, 1);
        insertRow(id, 1, "x");
        double hits = count("post", "hit");
        double misses = count("post", "miss");

        repository.findById(id);
        repository.findById(id);
        repository.findById(id);

        assertThat(count("post", "miss") - misses).isEqualTo(1);
        assertThat(count("post", "hit") - hits).isEqualTo(2);
    }

    private double count(String cache, String result) {
        var counter = meters.find("sns.cache.requests").tag("cache", cache).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private void insertRow(long id, long userId, String content) {
        jdbc.sql("INSERT INTO posts (id, user_id, content, like_count, created_at) VALUES (?, ?, ?, 0, ?)")
                .params(id, userId, content, Timestamp.from(Instant.now()))
                .update();
    }
}
