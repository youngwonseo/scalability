-- Tables are created without secondary indexes so bulk loading stays fast.
-- Secondary indexes live in indexes.sql and are added after loading.

DROP TABLE IF EXISTS posts;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
    id         BIGINT      NOT NULL,
    name       VARCHAR(50) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- No foreign key to users: it would add lock overhead and cannot span shards (S6).
CREATE TABLE posts (
    id         BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    content    VARCHAR(500) NOT NULL,
    like_count INT          NOT NULL DEFAULT 0,
    created_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;
