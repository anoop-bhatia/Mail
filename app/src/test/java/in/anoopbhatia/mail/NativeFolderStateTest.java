package in.anoopbhatia.mail;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class NativeFolderStateTest {
    @Test
    public void providerGateAllowsOnlyGraphAndZoho() {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        assertTrue(account.isGraphApi());
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        assertTrue(account.isZohoApi());
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        assertFalse(account.isGraphApi() || account.isZohoApi());
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        account.protocol = EntityAccount.TYPE_IMAP;
        assertFalse(account.isGraphApi() || account.isZohoApi());
    }

    @Test
    public void cursorIsDurableAndMalformedValuesRestartSafely() {
        assertEquals(0L, NativeFolderState.cursor(null));
        assertEquals(0L, NativeFolderState.cursor("broken"));
        assertEquals(0L, NativeFolderState.cursor("-4"));
        assertEquals(17L, NativeFolderState.cursor("17"));
    }

    @Test
    public void scanIsBoundedToAccountFolderAndVerifiedMembership() {
        String sql = DaoMessage.NATIVE_FOLDER_STATE_SCAN;
        assertTrue(sql.contains("message.account = :account"));
        assertTrue(sql.contains("folder.id = :folder"));
        assertTrue(sql.contains("message.id > :afterId"));
        assertTrue(sql.contains("ORDER BY message.id ASC LIMIT :limit"));
        assertTrue(sql.contains("message.graph_id IS NOT NULL OR message.zoho_id IS NOT NULL"));
        assertTrue(sql.contains(GmailLabelFolder.SQL_MESSAGE_IN_FOLDER));
        assertTrue("Explicit Zoho memberships must drive refresh candidates",
                sql.contains("zoho_folder_ids"));
    }
    @Test
    public void explicitScanReachesAndRemovesAStaleGraphRowBeyondTheFirstTen() throws Exception {
        try (java.sql.Connection db = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (java.sql.Statement statement = db.createStatement()) {
                statement.execute("CREATE TABLE folder(id INTEGER,account INTEGER,gmail_label_id TEXT,zoho_folder_id TEXT)");
                statement.execute("INSERT INTO folder VALUES(1,7,NULL,'101'),(2,7,NULL,'202')");
                statement.execute("CREATE TABLE message(id INTEGER PRIMARY KEY,account INTEGER,folder INTEGER,graph_id TEXT,gmail_id TEXT,labels TEXT,zoho_id TEXT,zoho_folder_ids TEXT)");
                statement.execute("CREATE TABLE operation(message INTEGER)");
                for (int id = 1; id <= 15; id++)
                    statement.execute("INSERT INTO message VALUES(" + id + ",7,1,'g" + id + "',NULL,NULL,NULL,NULL)");
                statement.execute("INSERT INTO message VALUES(16,8,1,'other-account',NULL,NULL,NULL,NULL)");
                statement.execute("INSERT INTO message VALUES(17,7,1,NULL,'gmail','INBOX',NULL,NULL)");
                statement.execute("INSERT INTO message VALUES(18,7,1,NULL,NULL,NULL,NULL,NULL)");
                statement.execute("INSERT INTO message VALUES(19,7,2,NULL,NULL,NULL,'98765','|101|')");
                statement.execute("INSERT INTO message VALUES(20,7,1,NULL,NULL,NULL,'98766','|202|')");
            }
            long cursor = 0;
            java.util.List<Long> visited = new java.util.ArrayList<>();
            int batches = 0;
            while (true) {
                java.util.List<Long> batch = scan(db, cursor);
                if (batch.isEmpty()) break;
                batches++;
                assertTrue(batch.size() <= NativeFolderState.BATCH_SIZE);
                for (long id : batch) {
                    visited.add(id);
                    cursor = id;
                    if (id == 15) {
                        String delete = DaoMessage.GRAPH_MEMBERSHIP_DELETE.replace(":id", "?")
                                .replace(":folder", "?").replace(":graphId", "?");
                        try (java.sql.PreparedStatement statement = db.prepareStatement(delete)) {
                            statement.setLong(1, id);
                            statement.setLong(2, 1);
                            statement.setString(3, "g15");
                            assertEquals(1, statement.executeUpdate());
                        }
                    }
                }
            }
            assertEquals(2, batches);
            assertTrue(visited.contains(15L));
            assertTrue(visited.contains(19L)); // secondary Zoho membership, not its physical folder
            assertFalse(visited.contains(16L));
            assertFalse(visited.contains(17L));
            assertFalse(visited.contains(18L));
            assertFalse(visited.contains(20L));
            try (java.sql.Statement statement = db.createStatement();
                 java.sql.ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM message WHERE id=15")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    private static java.util.List<Long> scan(java.sql.Connection db, long afterId) throws Exception {
        String sql = DaoMessage.NATIVE_FOLDER_STATE_SCAN.replace(":folder", "?")
                .replace(":account", "?").replace(":afterId", "?").replace(":limit", "?");
        java.util.List<Long> ids = new java.util.ArrayList<>();
        try (java.sql.PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setLong(1, 1);
            statement.setLong(2, 7);
            statement.setLong(3, afterId);
            statement.setInt(4, NativeFolderState.BATCH_SIZE);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getLong("id"));
            }
        }
        return ids;
    }
}
