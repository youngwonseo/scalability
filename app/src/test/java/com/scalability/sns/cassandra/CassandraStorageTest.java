package com.scalability.sns.cassandra;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.datastax.oss.driver.api.core.CqlSession;
import com.scalability.sns.id.SnowflakeIdGenerator;
import com.scalability.sns.post.PostRepository;

/** The API contract of PostApiTest, served from Cassandra (S5). */
@Testcontainers
@SpringBootTest
@ActiveProfiles("cassandra")
@AutoConfigureMockMvc
class CassandraStorageTest {

    @Container
    static CassandraContainer cassandra = new CassandraContainer("cassandra:5.0.4")
            .withEnv("MAX_HEAP_SIZE", "512M")
            .withEnv("HEAP_NEWSIZE", "128M");

    @DynamicPropertySource
    static void cassandraProperties(DynamicPropertyRegistry registry) {
        InetSocketAddress contactPoint = cassandra.getContactPoint();
        registry.add("sns.cassandra.contact-points", () -> contactPoint.getHostString() + ":" + contactPoint.getPort());
        registry.add("sns.cassandra.local-datacenter", cassandra::getLocalDatacenter);
        registry.add("sns.cassandra.replication-factor", () -> "1");
    }

    @BeforeAll
    static void createSchema() {
        try (CqlSession session = adminSession()) {
            CassandraSchema.recreate(session, "sns", cassandra.getLocalDatacenter(), 1);
        }
    }

    static CqlSession adminSession() {
        return CqlSession.builder()
                .addContactPoint(cassandra.getContactPoint())
                .withLocalDatacenter(cassandra.getLocalDatacenter())
                .build();
    }

    @Autowired
    MockMvcTester mvc;

    @Autowired
    PostRepository repository;

    @Autowired
    CqlSession session;

    @BeforeEach
    void clean() {
        session.execute("TRUNCATE posts_by_id");
        session.execute("TRUNCATE posts_by_user");
        session.execute("TRUNCATE post_likes");
    }

    @Test
    void usesCassandraRepository() {
        assertThat(repository).isInstanceOf(CassandraPostRepository.class);
    }

    @Test
    void createThenGetPost() {
        var created = mvc.post().uri("/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": 7, \"content\": \"hello\"}")
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        String id = created.getResponse().getHeader("Location").replace("/posts/", "");

        assertThat(mvc.get().uri("/posts/{id}", id))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.userId", v -> v.assertThat().isEqualTo(7))
                .hasPathSatisfying("$.content", v -> v.assertThat().isEqualTo("hello"))
                .hasPathSatisfying("$.likeCount", v -> v.assertThat().isEqualTo(0));
    }

    @Test
    void missingPostIs404ForReadAndLike() {
        assertThat(mvc.get().uri("/posts/{id}", 123)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.post().uri("/posts/{id}/like", 123)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void likesAreCountedInBothReadPaths() {
        var created = mvc.post().uri("/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": 4, \"content\": \"x\"}").exchange();
        String id = created.getResponse().getHeader("Location").replace("/posts/", "");

        assertThat(mvc.post().uri("/posts/{id}/like", id)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.post().uri("/posts/{id}/like", id)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(mvc.get().uri("/posts/{id}", id)).bodyJson()
                .hasPathSatisfying("$.likeCount", v -> v.assertThat().isEqualTo(2));
        assertThat(mvc.get().uri("/users/4/posts")).bodyJson()
                .hasPathSatisfying("$[0].likeCount", v -> v.assertThat().isEqualTo(2));
    }

    @Test
    void listsUserPostsNewestFirstWithCursor() {
        String[] ids = new String[5];
        for (int i = 0; i < 5; i++) {
            var created = mvc.post().uri("/posts").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userId\": 1, \"content\": \"p" + i + "\"}").exchange();
            ids[i] = created.getResponse().getHeader("Location").replace("/posts/", "");
        }
        mvc.post().uri("/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": 2, \"content\": \"other\"}").exchange();

        assertThat(mvc.get().uri("/users/1/posts?limit=2")).bodyJson()
                .extractingPath("$[*].id").asArray().containsExactly(ids[4], ids[3]);
        assertThat(mvc.get().uri("/users/1/posts?limit=10&before={b}", ids[3])).bodyJson()
                .extractingPath("$[*].id").asArray().containsExactly(ids[2], ids[1], ids[0]);
    }

    @Test
    void loaderWritesTheSharedDeterministicDataset() {
        try (CqlSession admin = adminSession()) {
            new CassandraDataLoader(admin, "sns_load_test", cassandra.getLocalDatacenter(), 1, 64).load(100, 2_500);
            for (long n : new long[] {1, 100, 101, 2_500}) {
                long owner = com.scalability.sns.loader.DataLoader.ownerOf(n, 100);
                var row = admin.execute("SELECT user_id FROM sns_load_test.posts_by_id WHERE id = ?",
                        SnowflakeIdGenerator.seededId(n, owner)).one();
                assertThat(row).isNotNull();
                assertThat(row.getLong("user_id")).isEqualTo(owner);
            }
            assertThat(admin.execute("SELECT COUNT(*) FROM sns_load_test.posts_by_user WHERE user_id = 1")
                    .one().getLong(0)).isEqualTo(25);
        }
    }
}
