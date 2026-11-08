package com.scalability.sns.post;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.scalability.sns.id.SnowflakeIdGenerator;

@Service
public class PostService {

    private final PostRepository repository;
    private final SnowflakeIdGenerator idGenerator;

    PostService(PostRepository repository, SnowflakeIdGenerator idGenerator) {
        this.repository = repository;
        this.idGenerator = idGenerator;
    }

    public Optional<Post> get(long id) {
        return repository.findById(id);
    }

    public List<Post> listByUser(long userId, Long beforeId, int limit) {
        return repository.findByUserId(userId, beforeId, limit);
    }

    public Post create(long userId, String content) {
        // DATETIME(3) keeps milliseconds, so truncate to match what is read back
        var post = new Post(idGenerator.nextId(userId), userId, content, 0, Instant.now().truncatedTo(ChronoUnit.MILLIS));
        repository.save(post);
        return post;
    }

    public boolean like(long id) {
        return repository.incrementLikeCount(id);
    }
}
