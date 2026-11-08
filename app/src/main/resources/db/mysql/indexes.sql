-- Serves GET /users/{id}/posts: WHERE user_id = ? [AND id < ?] ORDER BY id DESC LIMIT ?
CREATE INDEX idx_posts_user_id_id ON posts (user_id, id);
