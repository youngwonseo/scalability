package com.scalability.sns.cassandra;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.scalability.sns.post.Post;
import com.scalability.sns.post.PostRepository;

/**
 * Cassandra storage (S5). All statements run at the session's consistency (LOCAL_QUORUM).
 *
 * <p>A post is written to two tables with two parallel inserts, not a logged batch: faster, but a
 * failure between them can leave the tables inconsistent (an accepted trade-off for this experiment).
 */
public class CassandraPostRepository implements PostRepository {

    private static final String COLUMNS = "id, user_id, content, created_at";

    private final CqlSession session;
    private volatile Statements statements;

    public CassandraPostRepository(CqlSession session) {
        this.session = session;
    }

    /** Prepared on first use: the loader starts this app before the keyspace exists. */
    private record Statements(
            PreparedStatement selectById,
            PreparedStatement selectLikes,
            PreparedStatement selectLikesIn,
            PreparedStatement selectByUser,
            PreparedStatement selectByUserBefore,
            PreparedStatement insertById,
            PreparedStatement insertByUser,
            PreparedStatement existsById,
            PreparedStatement incrementLikes) {

        static Statements prepare(CqlSession session) {
            return new Statements(
                    session.prepare("SELECT " + COLUMNS + " FROM posts_by_id WHERE id = ?"),
                    session.prepare("SELECT like_count FROM post_likes WHERE id = ?"),
                    session.prepare("SELECT id, like_count FROM post_likes WHERE id IN ?"),
                    session.prepare("SELECT " + COLUMNS + " FROM posts_by_user WHERE user_id = ? LIMIT ?"),
                    session.prepare("SELECT " + COLUMNS + " FROM posts_by_user WHERE user_id = ? AND id < ? LIMIT ?"),
                    session.prepare("INSERT INTO posts_by_id (" + COLUMNS + ") VALUES (?, ?, ?, ?)"),
                    session.prepare("INSERT INTO posts_by_user (" + COLUMNS + ") VALUES (?, ?, ?, ?)"),
                    session.prepare("SELECT id FROM posts_by_id WHERE id = ?"),
                    session.prepare("UPDATE post_likes SET like_count = like_count + 1 WHERE id = ?"));
        }
    }

    private Statements st() {
        Statements current = statements;
        if (current == null) {
            synchronized (this) {
                current = statements;
                if (current == null) {
                    current = Statements.prepare(session);
                    statements = current;
                }
            }
        }
        return current;
    }

    @Override
    public Optional<Post> findById(long id) {
        CompletableFuture<AsyncResultSet> post = async(st().selectById().bind(id));
        CompletableFuture<AsyncResultSet> likes = async(st().selectLikes().bind(id));
        Row row = post.join().one();
        if (row == null) {
            return Optional.empty();
        }
        Row likeRow = likes.join().one();
        return Optional.of(map(row, likeRow == null ? 0 : likeRow.getLong("like_count")));
    }

    @Override
    public List<Post> findByUserId(long userId, Long beforeId, int limit) {
        ResultSet rows = beforeId == null
                ? session.execute(st().selectByUser().bind(userId, limit))
                : session.execute(st().selectByUserBefore().bind(userId, beforeId, limit));
        List<Row> posts = rows.all();
        if (posts.isEmpty()) {
            return List.of();
        }
        List<Long> ids = posts.stream().map(r -> r.getLong("id")).toList();
        Map<Long, Long> likes = new HashMap<>();
        for (Row r : session.execute(st().selectLikesIn().bind(ids))) {
            likes.put(r.getLong("id"), r.getLong("like_count"));
        }
        return posts.stream().map(r -> map(r, likes.getOrDefault(r.getLong("id"), 0L))).toList();
    }

    @Override
    public void save(Post post) {
        CompletableFuture.allOf(
                async(st().insertById().bind(post.id(), post.userId(), post.content(), post.createdAt())),
                async(st().insertByUser().bind(post.id(), post.userId(), post.content(), post.createdAt()))).join();
    }

    @Override
    public boolean incrementLikeCount(long id) {
        // counters upsert, so check existence first to keep the API's 404 semantics
        if (session.execute(st().existsById().bind(id)).one() == null) {
            return false;
        }
        session.execute(st().incrementLikes().bind(id));
        return true;
    }

    private CompletableFuture<AsyncResultSet> async(com.datastax.oss.driver.api.core.cql.Statement<?> statement) {
        return session.executeAsync(statement).toCompletableFuture();
    }

    private static Post map(Row row, long likeCount) {
        Instant createdAt = row.getInstant("created_at");
        return new Post(row.getLong("id"), row.getLong("user_id"), row.getString("content"),
                Math.toIntExact(likeCount), createdAt);
    }
}
