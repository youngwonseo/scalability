package com.scalability.sns.post;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.scalability.sns.storage.ConditionalOnMySqlStorage;

/** Storage implementation; {@code @Qualifier("storage")} lets the cache decorator (S4) wrap it. */
@Repository
@Qualifier("storage")
@ConditionalOnMySqlStorage
class MySqlPostRepository implements PostRepository {

    private static final String COLUMNS = "id, user_id, content, like_count, created_at";

    private final JdbcClient writer;
    private final JdbcClient reader;

    MySqlPostRepository(JdbcClient writer, @Qualifier("read") JdbcClient reader) {
        this.writer = writer;
        this.reader = reader;
    }

    @Override
    public Optional<Post> findById(long id) {
        return reader.sql("SELECT " + COLUMNS + " FROM posts WHERE id = ?")
                .param(id)
                .query(MySqlPostRepository::mapRow)
                .optional();
    }

    @Override
    public List<Post> findByUserId(long userId, Long beforeId, int limit) {
        if (beforeId == null) {
            return reader.sql("SELECT " + COLUMNS + " FROM posts WHERE user_id = ? ORDER BY id DESC LIMIT ?")
                    .params(userId, limit)
                    .query(MySqlPostRepository::mapRow)
                    .list();
        }
        return reader.sql("SELECT " + COLUMNS + " FROM posts WHERE user_id = ? AND id < ? ORDER BY id DESC LIMIT ?")
                .params(userId, beforeId, limit)
                .query(MySqlPostRepository::mapRow)
                .list();
    }

    @Override
    public void save(Post post) {
        writer.sql("INSERT INTO posts (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)")
                .params(post.id(), post.userId(), post.content(), post.likeCount(), Timestamp.from(post.createdAt()))
                .update();
    }

    @Override
    public boolean incrementLikeCount(long id) {
        return writer.sql("UPDATE posts SET like_count = like_count + 1 WHERE id = ?")
                .param(id)
                .update() == 1;
    }

    private static Post mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Post(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("content"),
                rs.getInt("like_count"),
                rs.getTimestamp("created_at").toInstant());
    }
}
