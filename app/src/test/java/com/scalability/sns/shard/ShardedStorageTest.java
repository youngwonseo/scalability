package com.scalability.sns.shard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.scalability.sns.loader.DataLoader;
import com.scalability.sns.post.PostRepository;

/** Two shards as two schemas in one MySQL container. Shard i holds users with (userId % 1024) % 2 == i. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("sharded")
@AutoConfigureMockMvc
class ShardedStorageTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4").withUsername("root").withPassword("root");

    static String shardUrl(int i) {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/shard_" + i;
    }

    @DynamicPropertySource
    static void shards(DynamicPropertyRegistry registry) {
        registry.add("sns.shard.urls", () -> shardUrl(0) + "," + shardUrl(1));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    @BeforeAll
    static void createShards() throws Exception {
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "root")) {
            c.createStatement().execute("CREATE DATABASE shard_0");
            c.createStatement().execute("CREATE DATABASE shard_1");
        }
        for (int i = 0; i < 2; i++) {
            try (Connection c = DriverManager.getConnection(shardUrl(i), "root", "root")) {
                ScriptUtils.executeSqlScript(c, new ClassPathResource("db/mysql/schema.sql"));
                ScriptUtils.executeSqlScript(c, new ClassPathResource("db/mysql/indexes.sql"));
            }
        }
    }

    static JdbcClient shard(int i) {
        return JdbcClient.create(new SingleConnectionDataSource(shardUrl(i), "root", "root", true));
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostRepository repository;

    @BeforeEach
    void clean() {
        shard(0).sql("DELETE FROM posts").update();
        shard(1).sql("DELETE FROM posts").update();
    }

    @Test
    void usesShardedRepository() {
        assertThat(repository).isInstanceOf(ShardedPostRepository.class);
    }

    @Test
    void postsLiveOnTheirUsersShardAndAreServedFromIt() {
        String odd = create(1, "user 1");   // shard 1
        String even = create(2, "user 2");  // shard 0

        assertThat(count(1, "user_id = 1")).isEqualTo(1);
        assertThat(count(0, "user_id = 1")).isZero();
        assertThat(count(0, "user_id = 2")).isEqualTo(1);

        assertThat(mvc.get().uri("/posts/{id}", odd)).bodyJson()
                .hasPathSatisfying("$.content", v -> v.assertThat().isEqualTo("user 1"));
        assertThat(mvc.get().uri("/posts/{id}", even)).bodyJson()
                .hasPathSatisfying("$.content", v -> v.assertThat().isEqualTo("user 2"));

        assertThat(mvc.post().uri("/posts/{id}/like", odd)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(shard(1).sql("SELECT like_count FROM posts WHERE id = ?").param(Long.parseLong(odd))
                .query(Integer.class).single()).isEqualTo(1);

        assertThat(mvc.get().uri("/users/2/posts")).bodyJson()
                .extractingPath("$[*].id").asArray().containsExactly(even);
    }

    @Test
    void missingPostIs404() {
        assertThat(mvc.get().uri("/posts/{id}", 123)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.post().uri("/posts/{id}/like", 123)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void loaderSplitsTheDatasetAcrossShards() {
        var router = new ShardRouter(2);
        for (int s = 0; s < 2; s++) {
            int shard = s;
            new DataLoader(new SingleConnectionDataSource(shardUrl(s), "root", "root", true), 1, 100,
                    userId -> router.shardOfUser(userId) == shard).load(100, 1_000);
        }

        assertThat(count(0, "TRUE") + count(1, "TRUE")).isEqualTo(1_000);
        assertThat(count(0, "MOD(user_id, 2) = 1")).isZero();
        assertThat(count(1, "MOD(user_id, 2) = 0")).isZero();
        assertThat(shard(0).sql("SELECT COUNT(*) FROM users").query(Long.class).single()).isEqualTo(50);
    }

    private String create(long userId, String content) {
        var created = mvc.post().uri("/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": " + userId + ", \"content\": \"" + content + "\"}").exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return created.getResponse().getHeader("Location").replace("/posts/", "");
    }

    private long count(int shard, String where) {
        return shard(shard).sql("SELECT COUNT(*) FROM posts WHERE " + where).query(Long.class).single();
    }
}
