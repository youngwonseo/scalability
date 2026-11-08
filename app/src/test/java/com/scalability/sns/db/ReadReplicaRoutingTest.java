package com.scalability.sns.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.scalability.sns.id.SnowflakeIdGenerator;

/**
 * Stands in for a replica with a second, independent schema in the same MySQL container: rows written
 * through the API land in "sns" (primary) and are only visible to reads once copied to "replica".
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/mysql/schema.sql,classpath:db/mysql/indexes.sql"
})
@AutoConfigureMockMvc
class ReadReplicaRoutingTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("sns")
            .withUsername("root")
            .withPassword("root");

    @DynamicPropertySource
    static void replica(DynamicPropertyRegistry registry) {
        registry.add("sns.replica.urls", () -> replicaUrl());
    }

    static String replicaUrl() {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/replica";
    }

    @BeforeAll
    static void createReplicaSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "root")) {
            c.createStatement().execute("CREATE DATABASE IF NOT EXISTS replica");
        }
        try (Connection c = DriverManager.getConnection(replicaUrl(), "root", "root")) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/mysql/schema.sql"));
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/mysql/indexes.sql"));
        }
    }

    @Autowired
    MockMvcTester mvc;

    JdbcClient replica;

    @BeforeEach
    void setUp() {
        replica = JdbcClient.create(new SingleConnectionDataSource(replicaUrl(), "root", "root", true));
        replica.sql("DELETE FROM posts").update();
    }

    @Test
    void writesGoToPrimaryAndReadsGoToReplica() {
        var created = mvc.post().uri("/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": 3, \"content\": \"hi\"}")
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        String id = created.getResponse().getHeader("Location").replace("/posts/", "");

        // not replicated yet: reads see the replica
        assertThat(mvc.get().uri("/posts/{id}", id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/users/3/posts")).bodyJson().isLenientlyEqualTo("[]");

        // likes are writes: they hit the primary, where the post exists
        assertThat(mvc.post().uri("/posts/{id}/like", id)).hasStatus(HttpStatus.NO_CONTENT);

        // "replicate"
        replica.sql("INSERT INTO posts (id, user_id, content, like_count, created_at) VALUES (?, 3, 'hi', 1, ?)")
                .params(Long.parseLong(id), java.sql.Timestamp.from(Instant.now()))
                .update();
        assertThat(mvc.get().uri("/posts/{id}", id)).hasStatusOk();
        assertThat(mvc.get().uri("/users/3/posts")).bodyJson()
                .extractingPath("$[*].id").asArray().containsExactly(id);
    }

    @Test
    void likeOnPostMissingFromPrimaryIsNotFoundEvenIfReplicaHasIt() {
        long id = SnowflakeIdGenerator.seededId(1, 1);
        replica.sql("INSERT INTO posts (id, user_id, content, like_count, created_at) VALUES (?, 1, 'x', 0, NOW())")
                .param(id).update();

        assertThat(mvc.post().uri("/posts/{id}/like", id)).hasStatus(HttpStatus.NOT_FOUND);
    }
}
