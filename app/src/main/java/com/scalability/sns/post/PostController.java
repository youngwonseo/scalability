package com.scalability.sns.post;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PostController {

    private final PostService service;

    PostController(PostService service) {
        this.service = service;
    }

    @GetMapping("/posts/{id}")
    ResponseEntity<PostResponse> get(@PathVariable long id) {
        return ResponseEntity.of(service.get(id).map(PostResponse::from));
    }

    @GetMapping("/users/{userId}/posts")
    List<PostResponse> listByUser(
            @PathVariable long userId,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.listByUser(userId, before, limit).stream().map(PostResponse::from).toList();
    }

    @PostMapping("/posts")
    ResponseEntity<PostResponse> create(@Valid @RequestBody CreatePostRequest request) {
        var post = service.create(request.userId(), request.content());
        return ResponseEntity.created(URI.create("/posts/" + post.id())).body(PostResponse.from(post));
    }

    @PostMapping("/posts/{id}/like")
    ResponseEntity<Void> like(@PathVariable long id) {
        return service.like(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    record CreatePostRequest(@Positive long userId, @NotBlank @Size(max = 500) String content) {
    }

    /** IDs are serialized as strings: runtime Snowflake IDs exceed JavaScript's safe integer range. */
    record PostResponse(String id, long userId, String content, int likeCount, Instant createdAt) {

        static PostResponse from(Post post) {
            return new PostResponse(
                    Long.toString(post.id()), post.userId(), post.content(), post.likeCount(), post.createdAt());
        }
    }
}
