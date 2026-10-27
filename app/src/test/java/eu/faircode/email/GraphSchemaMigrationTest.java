package eu.faircode.email;

import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class GraphSchemaMigrationTest {
    @Test
    public void additiveMigrationPreservesExistingRowsAndAddsAccountScopedGraphIdsAndCursors() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account (id INTEGER PRIMARY KEY, provider TEXT, auth_type INTEGER, user TEXT, password TEXT, name TEXT)");
            statement.execute("CREATE TABLE folder (id INTEGER PRIMARY KEY, account INTEGER NOT NULL, name TEXT NOT NULL)");
            statement.execute("CREATE TABLE message (id INTEGER PRIMARY KEY, account INTEGER NOT NULL, uid INTEGER)");
            statement.execute("INSERT INTO account (id, provider, auth_type, user, password, name) VALUES (7, 'office365', 1, 'saved@example.com', 'existing-token-state', 'Existing Outlook')");
            statement.execute("INSERT INTO folder (id, account, name) VALUES (1, 7, 'Inbox'), (2, 7, 'Projects')");
            statement.execute("INSERT INTO message (id, account, uid) VALUES (11, 7, 101), (12, 7, 102)");

            for (String sql : GraphSchemaMigration.SQL)
                statement.execute(sql);

            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, provider, auth_type, user, password, name FROM account WHERE id = 7")) {
                assertEquals(true, rows.next());
                assertEquals(7, rows.getLong("id"));
                assertEquals("office365", rows.getString("provider"));
                assertEquals(1, rows.getInt("auth_type"));
                assertEquals("saved@example.com", rows.getString("user"));
                assertEquals("existing-token-state", rows.getString("password"));
                assertEquals("Existing Outlook", rows.getString("name"));
                assertEquals(false, rows.next());
            }

            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, account, name, graph_folder_id, graph_page_link, graph_delta_link " +
                            "FROM folder ORDER BY id")) {
                assertEquals(true, rows.next());
                assertEquals(1, rows.getLong("id"));
                assertEquals(7, rows.getLong("account"));
                assertEquals("Inbox", rows.getString("name"));
                assertNull(rows.getString("graph_folder_id"));
                assertNull(rows.getString("graph_page_link"));
                assertNull(rows.getString("graph_delta_link"));
                assertEquals(true, rows.next());
                assertEquals("Projects", rows.getString("name"));
                assertEquals(false, rows.next());
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, account, uid, graph_id FROM message ORDER BY id")) {
                assertEquals(true, rows.next());
                assertEquals(11, rows.getLong("id"));
                assertEquals(7, rows.getLong("account"));
                assertEquals(101, rows.getLong("uid"));
                assertNull(rows.getString("graph_id"));
                assertEquals(true, rows.next());
                assertEquals(102, rows.getLong("uid"));
                assertNull(rows.getString("graph_id"));
                assertEquals(false, rows.next());
            }

            statement.execute("INSERT INTO message (id, account, graph_id) VALUES (13, 7, 'remote-id'), (14, 8, 'remote-id')");
            statement.execute("INSERT INTO message (id, account) VALUES (15, 7), (16, 7)");
            statement.execute("UPDATE folder SET graph_folder_id = 'folder-id', " +
                    "graph_page_link = 'https://graph.microsoft.com/v1.0/page', " +
                    "graph_delta_link = 'https://graph.microsoft.com/v1.0/delta' WHERE id = 1");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT graph_folder_id, graph_page_link, graph_delta_link FROM folder WHERE id = 1")) {
                assertEquals(true, rows.next());
                assertEquals("folder-id", rows.getString("graph_folder_id"));
                assertEquals("https://graph.microsoft.com/v1.0/page", rows.getString("graph_page_link"));
                assertEquals("https://graph.microsoft.com/v1.0/delta", rows.getString("graph_delta_link"));
                assertEquals(false, rows.next());
            }

            try {
                statement.execute("INSERT INTO message (id, account, graph_id) VALUES (17, 7, 'remote-id')");
                fail("Duplicate Graph message ID in one account must be rejected");
            } catch (SQLException expected) {
                // Same Graph ID can exist in another account; only duplicates within one mailbox are invalid.
            }
        }
    }
}
