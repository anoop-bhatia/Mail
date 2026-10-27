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

public class ZohoSchemaMigrationTest {
    @Test
    public void migrationPreservesRowsAndAddsBoundedFolderOffsetsAndOpaqueIds() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE folder (id INTEGER PRIMARY KEY, account INTEGER NOT NULL, name TEXT NOT NULL, " +
                    "type TEXT NOT NULL, inherited_type TEXT, display TEXT, color INTEGER, unified INTEGER NOT NULL DEFAULT 0, " +
                    "notify INTEGER NOT NULL DEFAULT 0, read_only INTEGER NOT NULL DEFAULT 0, gmail_label_id TEXT)");
            statement.execute("CREATE TABLE message (id INTEGER PRIMARY KEY, account INTEGER NOT NULL, uid INTEGER)");
            statement.execute("INSERT INTO folder (id, account, name, type) " +
                    "VALUES (1, 7, 'INBOX', 'inbox'), (2, 7, 'Projects', 'user')");
            statement.execute("INSERT INTO message (id, account, uid) VALUES (11, 7, 41), (12, 7, 42)");
            for (String sql : ZohoSchemaMigration.SQL)
                statement.execute(sql);
            for (String sql : ZohoSchemaMigration.RECENT_SCAN_SQL)
                statement.execute(sql);

            statement.execute("UPDATE folder SET zoho_folder_id = 'folder-A' WHERE id = 1");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, zoho_folder_id FROM folder_view ORDER BY id")) {
                assertEquals(true, rows.next());
                assertEquals(1, rows.getLong("id"));
                assertEquals("folder-A", rows.getString("zoho_folder_id"));
                assertEquals(true, rows.next());
                assertNull(rows.getString("zoho_folder_id"));
                assertEquals(false, rows.next());
            }

            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, account, name, zoho_folder_id, zoho_page_start, " +
                            "zoho_recent_page_start, zoho_recent_scan_head_id, " +
                            "zoho_recent_boundary_id FROM folder ORDER BY id")) {
                assertEquals(true, rows.next());
                assertEquals(1, rows.getLong("id"));
                assertEquals(7, rows.getLong("account"));
                assertEquals("INBOX", rows.getString("name"));
                assertEquals("folder-A", rows.getString("zoho_folder_id"));
                assertEquals(1, rows.getInt("zoho_page_start"));
                assertNull(rows.getObject("zoho_recent_page_start"));
                assertNull(rows.getString("zoho_recent_scan_head_id"));
                assertNull(rows.getString("zoho_recent_boundary_id"));
                assertEquals(true, rows.next());
                assertEquals("Projects", rows.getString("name"));
                assertEquals(1, rows.getInt("zoho_page_start"));
                assertNull(rows.getObject("zoho_recent_page_start"));
                assertNull(rows.getString("zoho_recent_scan_head_id"));
                assertNull(rows.getString("zoho_recent_boundary_id"));
                assertEquals(false, rows.next());
            }

            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, account, uid, zoho_id FROM message ORDER BY id")) {
                assertEquals(true, rows.next());
                assertEquals(11, rows.getLong("id"));
                assertEquals(7, rows.getLong("account"));
                assertEquals(41, rows.getLong("uid"));
                assertNull(rows.getString("zoho_id"));
                assertEquals(true, rows.next());
                assertEquals(42, rows.getLong("uid"));
                assertNull(rows.getString("zoho_id"));
                assertEquals(false, rows.next());
            }

            statement.execute("INSERT INTO message (id, account, uid, zoho_id) VALUES (13, 7, NULL, 'opaque-A')");
            statement.execute("INSERT INTO message (id, account, uid, zoho_id) VALUES (14, 8, NULL, 'opaque-A')");
            statement.execute("INSERT INTO message (id, account, uid) VALUES (15, 7, NULL)");
            statement.execute("INSERT INTO message (id, account, uid) VALUES (16, 7, NULL)");
            try {
                statement.execute("INSERT INTO message (id, account, uid, zoho_id) VALUES (17, 7, NULL, 'opaque-A')");
                fail("Duplicate Zoho message ID in one account must be rejected");
            } catch (SQLException expected) {
                // Composite unique index allows the same opaque ID in another account.
            }
        }
    }
}
