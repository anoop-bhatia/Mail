package in.anoopbhatia.mail;

import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ThreadQueryVisibilityTest {
    @Test
    public void classifiedHiddenAnchorRemainsVisibleWithoutShowingOtherHiddenRows() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            createMessages(connection);

            assertEquals(Arrays.asList(1L, 3L), queryThread(connection, true, 1L, false));
        }
    }

    @Test
    public void nonThreadedReaderStillRestrictsResultsToTheSelectedMessage() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            createMessages(connection);

            assertEquals(Arrays.asList(1L), queryThread(connection, false, 1L, false));
        }
    }

    @Test
    public void debugModeStillShowsAllHiddenThreadMessages() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            createMessages(connection);

            assertEquals(Arrays.asList(1L, 2L, 3L), queryThread(connection, true, 1L, true));
        }
    }

    @Test
    public void graphDetailAnchorSurvivesRekeyButUnreadListDoesNotKeepReadSourceAnchor() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE message (id INTEGER PRIMARY KEY, account INTEGER NOT NULL, thread TEXT, graph_id TEXT, ui_seen INTEGER NOT NULL, ui_hide INTEGER NOT NULL)");
                statement.execute("INSERT INTO message VALUES (1, 7, 'graph:7:new', 'graph-message', 1, 0)");
                statement.execute("INSERT INTO message VALUES (2, 7, 'graph:7:old', 'graph-message-2', 0, 0)");
                statement.execute("INSERT INTO message VALUES (3, 8, 'graph:7:old', 'other-account', 0, 0)");
                statement.execute("INSERT INTO message VALUES (4, 7, 'graph:7:wrong', NULL, 0, 0)");
            }
            assertEquals(Arrays.asList(2L), queryGraphThread(connection, 7L, "graph:7:old", 1L, true));
            assertEquals(Arrays.asList(1L, 2L), queryGraphThread(connection, 7L, "graph:7:old", 1L, false));
        }
    }

    @Test
    public void nonThreadedGraphReaderRetainsOnlyItsSelectedAnchorAfterRekeyAndRead() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            createRekeyedMessages(connection);
            assertEquals(Arrays.asList(1L), queryReader(connection, 7L, 1L, false));
            assertEquals(Arrays.asList(1L, 2L), queryReader(connection, 7L, 1L, true));
            assertEquals(Arrays.asList(), queryReader(connection, 8L, 1L, false));
        }
    }

    @Test
    public void rekeyedNonGraphMessagesKeepTheExistingImapPopMembershipRules() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            createRekeyedMessages(connection);
            assertEquals(Arrays.asList(), queryReader(connection, 7L, 4L, false));
            assertEquals(Arrays.asList(2L), queryReader(connection, 7L, 4L, true));
        }
    }

    private static void createRekeyedMessages(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE message (id INTEGER PRIMARY KEY, account INTEGER, thread TEXT, graph_id TEXT, ui_seen INTEGER, ui_hide INTEGER)");
            statement.execute("INSERT INTO message VALUES (1, 7, 'new', 'graph-selected', 1, 1)");
            statement.execute("INSERT INTO message VALUES (2, 7, 'old', 'graph-sibling', 0, 0)");
            statement.execute("INSERT INTO message VALUES (3, 7, 'new', 'graph-unrelated', 0, 0)");
            statement.execute("INSERT INTO message VALUES (4, 7, 'new', NULL, 1, 0)");
        }
    }

    private static List<Long> queryReader(Connection connection, long account, long anchor,
                                           boolean threading) throws SQLException {
        String sql = "SELECT message.id FROM message WHERE message.account = ? AND " +
                DaoMessage.THREAD_QUERY_MEMBERSHIP.replace(":thread", "?").replace(":id", "?") +
                " AND " + DaoMessage.THREAD_QUERY_ID_FILTER.replace(":threading", "?").replace(":id", "?") +
                " AND " + DaoMessage.THREAD_QUERY_VISIBILITY.replace(":debug", "?").replace(":id", "?") +
                " ORDER BY message.id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, account);
            statement.setString(2, "old");
            statement.setLong(3, anchor);
            statement.setBoolean(4, threading);
            statement.setLong(5, anchor);
            statement.setLong(6, anchor);
            statement.setBoolean(7, false);
            statement.setLong(8, anchor);
            List<Long> ids = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next())
                    ids.add(result.getLong(1));
            }
            return ids;
        }
    }

    private static void createMessages(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE message (id INTEGER PRIMARY KEY, thread TEXT, ui_hide INTEGER NOT NULL)");
            statement.execute("INSERT INTO message VALUES (1, 'conversation', 1)");
            statement.execute("INSERT INTO message VALUES (2, 'conversation', 1)");
            statement.execute("INSERT INTO message VALUES (3, 'conversation', 0)");
        }
    }

    private static List<Long> queryThread(Connection connection, boolean threading,
                                          Long anchorId, boolean debug) throws SQLException {
        String idFilter = DaoMessage.THREAD_QUERY_ID_FILTER
                .replace(":threading", "?")
                .replace(":id", "?");
        String visibility = DaoMessage.THREAD_QUERY_VISIBILITY
                .replace(":debug", "?")
                .replace(":id", "?");
        String sql = "SELECT message.id FROM message WHERE message.thread = ? AND " +
                idFilter + " AND " + visibility + " ORDER BY message.id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "conversation");
            statement.setBoolean(2, threading);
            setNullableId(statement, 3, anchorId);
            setNullableId(statement, 4, anchorId);
            statement.setBoolean(5, debug);
            setNullableId(statement, 6, anchorId);
            List<Long> ids = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next())
                    ids.add(result.getLong(1));
            }
            return ids;
        }
    }

    private static void setNullableId(PreparedStatement statement, int index, Long id) throws SQLException {
        if (id == null)
            statement.setNull(index, Types.BIGINT);
        else
            statement.setLong(index, id);
    }

    private static List<Long> queryGraphThread(Connection connection, long account, String thread,
                                               long anchorId, boolean unreadOnly) throws SQLException {
        String membership = DaoMessage.THREAD_QUERY_MEMBERSHIP
                .replace(":thread", "?").replace(":id", "?");
        String sql = "SELECT message.id FROM message WHERE message.account = ? AND " + membership +
                " AND (NOT ? OR NOT message.ui_seen) ORDER BY message.id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, account);
            statement.setString(2, thread);
            statement.setLong(3, anchorId);
            statement.setBoolean(4, unreadOnly);
            List<Long> ids = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next())
                    ids.add(result.getLong(1));
            }
            return ids;
        }
    }
}
