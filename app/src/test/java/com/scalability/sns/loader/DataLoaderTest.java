package com.scalability.sns.loader;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.scalability.sns.MySqlIntegrationTest;
import com.scalability.sns.id.SnowflakeIdGenerator;

@MySqlIntegrationTest
class DataLoaderTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Test
    void loadsDeterministicDataset() {
        new DataLoader(dataSource, 4, 100).load(100, 2_500);

        assertThat(count("users")).isEqualTo(100);
        assertThat(count("posts")).isEqualTo(2_500);

        // the k6 scripts compute ids from n; spot-check that those rows exist with the right owner
        for (long n : new long[] {1, 100, 101, 1_234, 2_500}) {
            long owner = DataLoader.ownerOf(n, 100);
            Long userId = jdbc.sql("SELECT user_id FROM posts WHERE id = ?")
                    .param(SnowflakeIdGenerator.seededId(n, owner))
                    .query(Long.class)
                    .single();
            assertThat(userId).isEqualTo(owner);
        }

        // every user has the same number of posts
        assertThat(jdbc.sql("SELECT COUNT(DISTINCT c) FROM (SELECT COUNT(*) c FROM posts GROUP BY user_id) t")
                .query(Long.class).single()).isEqualTo(1);

        // the secondary index is created after loading
        assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND index_name = 'idx_posts_user_id_id'")
                .query(Long.class).single()).isPositive();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
