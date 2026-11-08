package com.scalability.sns.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;

/**
 * Query-first model (S5): one table per access path instead of the MySQL schema.
 *
 * <ul>
 *   <li>{@code posts_by_id}: GET /posts/{id}</li>
 *   <li>{@code posts_by_user}: GET /users/{id}/posts — one partition per user, newest first</li>
 *   <li>{@code post_likes}: like counts. Counters cannot share a table with regular columns.</li>
 * </ul>
 */
public final class CassandraSchema {

    private CassandraSchema() {
    }

    public static void recreate(CqlSession session, String keyspace, String datacenter, int replicationFactor) {
        session.execute("DROP KEYSPACE IF EXISTS " + keyspace);
        session.execute("CREATE KEYSPACE " + keyspace + " WITH replication = "
                + "{'class': 'NetworkTopologyStrategy', '" + datacenter + "': " + replicationFactor + "}");
        session.execute("CREATE TABLE " + keyspace + ".posts_by_id ("
                + "id bigint PRIMARY KEY, user_id bigint, content text, created_at timestamp)");
        session.execute("CREATE TABLE " + keyspace + ".posts_by_user ("
                + "user_id bigint, id bigint, content text, created_at timestamp, "
                + "PRIMARY KEY ((user_id), id)) WITH CLUSTERING ORDER BY (id DESC)");
        session.execute("CREATE TABLE " + keyspace + ".post_likes (id bigint PRIMARY KEY, like_count counter)");
    }
}
