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

public class GmailSchemaMigrationTest {
    @Test
    public void additiveMigrationPreservesRowsAndScopesNativeIdUniquenessByAccount() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE message (id INTEGER PRIMARY KEY, account INTEGER NOT NULL, uid INTEGER)");
            statement.execute("INSERT INTO message (id, account, uid) VALUES (1, 7, 31), (2, 7, 32)");
            for (String sql : GmailSchemaMigration.SQL)
                statement.execute(sql);

            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, account, uid, gmail_id, gmail_thread_id FROM message ORDER BY id")) {
                assertEquals(true, rows.next());
                assertEquals(1, rows.getLong("id"));
                assertEquals(7, rows.getLong("account"));
                assertEquals(31, rows.getLong("uid"));
                assertNull(rows.getString("gmail_id"));
                assertNull(rows.getString("gmail_thread_id"));
                assertEquals(true, rows.next());
                assertEquals(32, rows.getLong("uid"));
                assertNull(rows.getString("gmail_id"));
                assertEquals(false, rows.next());
            }

            statement.execute("INSERT INTO message (id, account, uid, gmail_id) VALUES (3, 7, NULL, 'native-A')");
            statement.execute("INSERT INTO message (id, account, uid, gmail_id) VALUES (4, 8, NULL, 'native-A')");
            statement.execute("INSERT INTO message (id, account, uid) VALUES (5, 7, NULL)");
            statement.execute("INSERT INTO message (id, account, uid) VALUES (6, 7, NULL)");
            try {
                statement.execute("INSERT INTO message (id, account, uid, gmail_id) VALUES (7, 7, NULL, 'native-A')");
                fail("Duplicate native Gmail ID in one account must be rejected");
            } catch (SQLException expected) {
                // The composite native-ID index rejects duplicates without changing uid/uidl.
            }
        }
    }

    @Test
    public void folderLabelMigrationPreservesExistingFoldersAndAddsNullableMapping() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE folder (id INTEGER PRIMARY KEY, account INTEGER NOT NULL, name TEXT NOT NULL, type TEXT, inherited_type TEXT, display TEXT, color INTEGER, unified INTEGER, notify INTEGER, read_only INTEGER)");
            statement.execute("INSERT INTO folder (id, account, name) VALUES (1, 7, 'INBOX'), (2, 7, 'Drafts')");
            for (String sql : GmailLabelFolderMigration.SQL)
                statement.execute(sql);

            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, account, name, gmail_label_id FROM folder ORDER BY id")) {
                assertEquals(true, rows.next());
                assertEquals(1, rows.getLong("id"));
                assertEquals(7, rows.getLong("account"));
                assertEquals("INBOX", rows.getString("name"));
                assertNull(rows.getString("gmail_label_id"));
                assertEquals(true, rows.next());
                assertEquals("Drafts", rows.getString("name"));
                assertNull(rows.getString("gmail_label_id"));
                assertEquals(false, rows.next());
            }

            statement.execute("INSERT INTO folder (id, account, name, gmail_label_id) VALUES (3, 7, '__gmail_api_label__Label_42', 'Label_42')");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT gmail_label_id FROM folder WHERE id = 3")) {
                assertEquals(true, rows.next());
                assertEquals("Label_42", rows.getString("gmail_label_id"));
                assertEquals(false, rows.next());
            }

            try (ResultSet rows = statement.executeQuery(
                    "SELECT gmail_label_id FROM folder_view WHERE id = 3")) {
                assertEquals(true, rows.next());
                assertEquals("Label_42", rows.getString("gmail_label_id"));
                assertEquals(false, rows.next());
            }
        }
    }

    @Test
    public void gmailCursorMigrationPreservesAccountsAndStoresResumableTokens() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            statement.execute("INSERT INTO account (id, name) VALUES (7, 'Gmail')");
            for (String sql : GmailSyncMigration.SQL)
                statement.execute(sql);

            statement.execute("UPDATE account SET gmail_full_sync_history_id = 'history-start', " +
                    "gmail_full_sync_page_token = 'page-2' WHERE id = 7");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, name, gmail_history_id, gmail_full_sync_history_id, " +
                            "gmail_full_sync_page_token, gmail_history_page_token FROM account WHERE id = 7")) {
                assertEquals(true, rows.next());
                assertEquals(7, rows.getLong("id"));
                assertEquals("Gmail", rows.getString("name"));
                assertNull(rows.getString("gmail_history_id"));
                assertEquals("history-start", rows.getString("gmail_full_sync_history_id"));
                assertEquals("page-2", rows.getString("gmail_full_sync_page_token"));
                assertNull(rows.getString("gmail_history_page_token"));
                assertEquals(false, rows.next());
            }
        }
    }

    @Test
    public void gmailFolderPageMigrationPreservesDataAndAddsBothResumabilityColumns() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE folder (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            statement.execute("CREATE TABLE account (id INTEGER PRIMARY KEY, name TEXT NOT NULL, gmail_history_id TEXT)");
            statement.execute("INSERT INTO folder (id, name) VALUES (1, 'INBOX'), (2, 'Projects')");
            statement.execute("INSERT INTO account (id, name, gmail_history_id) VALUES (7, 'Gmail', 'history-10')");
            for (String sql : GmailFolderPageMigration.SQL)
                statement.execute(sql);

            statement.execute("UPDATE folder SET gmail_page_token = 'inbox-page-2' WHERE id = 1");
            statement.execute("UPDATE folder SET gmail_page_token = 'projects-page-2' WHERE id = 2");
            statement.execute("UPDATE account SET gmail_history_pending = '{\"changes\":[]}' WHERE id = 7");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT name, gmail_page_token FROM folder WHERE id = 1")) {
                assertEquals(true, rows.next());
                assertEquals("INBOX", rows.getString("name"));
                assertEquals("inbox-page-2", rows.getString("gmail_page_token"));
                assertEquals(false, rows.next());
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT name, gmail_page_token FROM folder WHERE id = 2")) {
                assertEquals(true, rows.next());
                assertEquals("Projects", rows.getString("name"));
                assertEquals("projects-page-2", rows.getString("gmail_page_token"));
                assertEquals(false, rows.next());
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT name, gmail_history_id, gmail_history_pending FROM account WHERE id = 7")) {
                assertEquals(true, rows.next());
                assertEquals("Gmail", rows.getString("name"));
                assertEquals("history-10", rows.getString("gmail_history_id"));
                assertEquals("{\"changes\":[]}", rows.getString("gmail_history_pending"));
                assertEquals(false, rows.next());
            }
        }
    }

    @Test
    public void gmailReceivedDateMigrationRestartsOnlyGmailRestFolderPagesAndPreservesAccountsAndMessages() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account (id INTEGER PRIMARY KEY, auth_type INTEGER NOT NULL, " +
                    "host TEXT NOT NULL, password TEXT NOT NULL, gmail_history_id TEXT, " +
                    "gmail_full_sync_history_id TEXT, gmail_full_sync_page_token TEXT, " +
                    "gmail_history_page_token TEXT, gmail_history_pending TEXT)");
            statement.execute("INSERT INTO account VALUES " +
                    "(7, 2, 'imap.gmail.com', 'rest-state', 'history-7', 'full-history-7', " +
                    "'account-full-page-7', 'account-history-page-7', '{\"changes\":[7]}'), " +
                    "(8, 3, 'imap.gmail.com', 'legacy-state', 'history-8', 'full-history-8', " +
                    "'account-full-page-8', 'account-history-page-8', '{\"changes\":[8]}'), " +
                    "(9, 1, 'imap.example.net', 'other-state', 'history-9', 'full-history-9', " +
                    "'account-full-page-9', 'account-history-page-9', '{\"changes\":[9]}'), " +
                    "(10, 3, 'SMTP.Googlemail.com', '{\"version\": 2, \"token\": \"fixture\"}', 'history-10', 'full-history-10', " +
                    "'account-full-page-10', 'account-history-page-10', '{\"changes\":[10]}'), " +
                    "(11, 3, 'imap.gmail.com', '{\"version\":20}', 'history-11', 'full-history-11', " +
                    "'account-full-page-11', 'account-history-page-11', '{\"changes\":[11]}')");
            statement.execute("CREATE TABLE folder (id INTEGER PRIMARY KEY, account INTEGER, gmail_page_token TEXT)");
            statement.execute("INSERT INTO folder VALUES " +
                    "(1, 7, 'gmail-inbox-page'), (2, 7, 'gmail-label-page'), " +
                    "(3, 8, 'legacy-imap-page'), (4, 9, 'other-provider-page'), " +
                    "(5, 10, 'fallback-gmail-page'), (6, 11, 'future-version-state-page')");
            statement.execute("CREATE TABLE message (id INTEGER PRIMARY KEY, account INTEGER NOT NULL, " +
                    "gmail_id TEXT, received INTEGER NOT NULL, sent INTEGER, subject TEXT, " +
                    "content INTEGER NOT NULL, seen INTEGER NOT NULL, flagged INTEGER NOT NULL, labels TEXT)");
            statement.execute("INSERT INTO message " +
                    "(id, account, gmail_id, received, sent, subject, content, seen, flagged, labels) " +
                    "VALUES (17, 7, 'linked-17', 1000, 2000, 'preserve me', 1, 1, 1, 'inbox-starred'), " +
                    "(18, 8, NULL, 3000, 4000, 'legacy row', 0, 0, 1, 'local-label')");

            for (String sql : GmailReceivedDateMigration.SQL)
                statement.execute(sql);

            try (ResultSet rows = statement.executeQuery(
                    "SELECT account, gmail_page_token FROM folder ORDER BY id")) {
                assertEquals(true, rows.next());
                assertEquals(7, rows.getLong("account"));
                assertNull(rows.getString("gmail_page_token"));
                assertEquals(true, rows.next());
                assertEquals(7, rows.getLong("account"));
                assertNull(rows.getString("gmail_page_token"));
                assertEquals(true, rows.next());
                assertEquals(8, rows.getLong("account"));
                assertEquals("legacy-imap-page", rows.getString("gmail_page_token"));
                assertEquals(true, rows.next());
                assertEquals(9, rows.getLong("account"));
                assertEquals("other-provider-page", rows.getString("gmail_page_token"));
                assertEquals(true, rows.next());
                assertEquals(10, rows.getLong("account"));
                assertNull(rows.getString("gmail_page_token"));
                assertEquals(true, rows.next());
                assertEquals(11, rows.getLong("account"));
                assertEquals("future-version-state-page", rows.getString("gmail_page_token"));
                assertEquals(false, rows.next());
            }

            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, gmail_history_id, gmail_full_sync_history_id, gmail_full_sync_page_token, " +
                            "gmail_history_page_token, gmail_history_pending FROM account ORDER BY id")) {
                for (int id = 7; id <= 11; id++) {
                    assertEquals(true, rows.next());
                    assertEquals(id, rows.getLong("id"));
                    assertEquals("history-" + id, rows.getString("gmail_history_id"));
                    assertEquals("full-history-" + id, rows.getString("gmail_full_sync_history_id"));
                    assertEquals("account-full-page-" + id, rows.getString("gmail_full_sync_page_token"));
                    assertEquals("account-history-page-" + id, rows.getString("gmail_history_page_token"));
                    assertEquals("{\"changes\":[" + id + "]}", rows.getString("gmail_history_pending"));
                }
                assertEquals(false, rows.next());
            }

            // A later marker value is unaffected by the folder-only reset SQL as well.
            statement.execute("UPDATE message SET gmail_received_checked = 1 WHERE id = 18");
            statement.execute(GmailReceivedDateMigration.SQL[1]);
            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, account, gmail_id, received, sent, subject, content, seen, flagged, labels, " +
                            "gmail_received_checked FROM message ORDER BY id")) {
                assertEquals(true, rows.next());
                assertEquals(17, rows.getLong("id"));
                assertEquals(7, rows.getLong("account"));
                assertEquals("linked-17", rows.getString("gmail_id"));
                assertEquals(1000L, rows.getLong("received"));
                assertEquals(2000L, rows.getLong("sent"));
                assertEquals("preserve me", rows.getString("subject"));
                assertEquals(1, rows.getInt("content"));
                assertEquals(1, rows.getInt("seen"));
                assertEquals(1, rows.getInt("flagged"));
                assertEquals("inbox-starred", rows.getString("labels"));
                assertEquals(0, rows.getInt("gmail_received_checked"));
                assertEquals(true, rows.next());
                assertEquals(18, rows.getLong("id"));
                assertEquals(8, rows.getLong("account"));
                assertNull(rows.getString("gmail_id"));
                assertEquals(3000L, rows.getLong("received"));
                assertEquals(4000L, rows.getLong("sent"));
                assertEquals("legacy row", rows.getString("subject"));
                assertEquals(0, rows.getInt("content"));
                assertEquals(0, rows.getInt("seen"));
                assertEquals(1, rows.getInt("flagged"));
                assertEquals("local-label", rows.getString("labels"));
                assertEquals(1, rows.getInt("gmail_received_checked"));
                assertEquals(false, rows.next());
            }
        }
    }
}
