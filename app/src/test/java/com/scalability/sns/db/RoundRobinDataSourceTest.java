package com.scalability.sns.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

class RoundRobinDataSourceTest {

    @Test
    void cyclesThroughDelegates() throws Exception {
        List<DataSource> delegates = new ArrayList<>();
        List<Connection> connections = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DataSource ds = mock(DataSource.class);
            Connection c = mock(Connection.class);
            when(ds.getConnection()).thenReturn(c);
            delegates.add(ds);
            connections.add(c);
        }
        var roundRobin = new RoundRobinDataSource(delegates);

        List<Connection> got = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            got.add(roundRobin.getConnection());
        }

        assertThat(got).containsExactly(
                connections.get(0), connections.get(1), connections.get(2),
                connections.get(0), connections.get(1), connections.get(2));
    }

    @Test
    void rejectsEmptyDelegates() {
        assertThatThrownBy(() -> new RoundRobinDataSource(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
