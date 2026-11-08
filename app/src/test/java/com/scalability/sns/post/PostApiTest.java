package com.scalability.sns.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.scalability.sns.MySqlIntegrationTest;
import com.scalability.sns.id.SnowflakeIdGenerator;

@MySqlIntegrationTest
@AutoConfigureMockMvc
class PostApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM posts").update();
    }

    @Test
    void createThenGetPost() {
        var created = mvc.post().uri("/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": 7, \"content\": \"hello\"}")
                .exchange();

        assertThat(created).hasStatus(HttpStatus.CREATED);
        String id = created.getResponse().getHeader("Location").replace("/posts/", "");
        assertThat(SnowflakeIdGenerator.logicalShardOf(Long.parseLong(id))).isEqualTo(7);

        assertThat(mvc.get().uri("/posts/{id}", id))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.id", v -> v.assertThat().isEqualTo(id))
                .hasPathSatisfying("$.userId", v -> v.assertThat().isEqualTo(7))
                .hasPathSatisfying("$.content", v -> v.assertThat().isEqualTo("hello"))
                .hasPathSatisfying("$.likeCount", v -> v.assertThat().isEqualTo(0));
    }

    @Test
    void getMissingPostReturns404() {
        assertThat(mvc.get().uri("/posts/{id}", 123)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void createRejectsInvalidRequest() {
        assertThat(mvc.post().uri("/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": 0, \"content\": \"\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listsUserPostsNewestFirstWithCursor() {
        for (long n = 1; n <= 5; n++) {
            insert(SnowflakeIdGenerator.seededId(n, 1), 1);
        }
        insert(SnowflakeIdGenerator.seededId(6, 2), 2);

        assertThat(mvc.get().uri("/users/1/posts?limit=2"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[*].id")
                .asArray()
                .containsExactly(id(5, 1), id(4, 1));

        assertThat(mvc.get().uri("/users/1/posts?limit=10&before={before}", id(4, 1)))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[*].id")
                .asArray()
                .containsExactly(id(3, 1), id(2, 1), id(1, 1));
    }

    @Test
    void listRejectsLimitOutOfRange() {
        assertThat(mvc.get().uri("/users/1/posts?limit=101")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void likeIncrementsCount() {
        long postId = SnowflakeIdGenerator.seededId(1, 1);
        insert(postId, 1);

        assertThat(mvc.post().uri("/posts/{id}/like", postId)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.post().uri("/posts/{id}/like", postId)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(mvc.get().uri("/posts/{id}", postId))
                .bodyJson()
                .hasPathSatisfying("$.likeCount", v -> v.assertThat().isEqualTo(2));
    }

    @Test
    void likeMissingPostReturns404() {
        assertThat(mvc.post().uri("/posts/{id}/like", 999)).hasStatus(HttpStatus.NOT_FOUND);
    }

    private void insert(long id, long userId) {
        jdbc.sql("INSERT INTO posts (id, user_id, content, like_count, created_at) VALUES (?, ?, 'c', 0, ?)")
                .params(id, userId, java.sql.Timestamp.from(Instant.now()))
                .update();
    }

    private static String id(long n, long userId) {
        return Long.toString(SnowflakeIdGenerator.seededId(n, userId));
    }
}
