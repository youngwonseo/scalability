package com.scalability.sns.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.AbstractDataSource;

/** Hands out connections from each delegate in turn (one delegate per read replica). */
public class RoundRobinDataSource extends AbstractDataSource {

    private final List<DataSource> delegates;
    private final AtomicInteger next = new AtomicInteger();

    public RoundRobinDataSource(List<? extends DataSource> delegates) {
        if (delegates.isEmpty()) {
            throw new IllegalArgumentException("At least one delegate is required");
        }
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return pick().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return pick().getConnection(username, password);
    }

    private DataSource pick() {
        return delegates.get(Math.floorMod(next.getAndIncrement(), delegates.size()));
    }
}
