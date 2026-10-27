package eu.faircode.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GmailLabelFolderTest {
    @Test
    public void mapsSystemAndUserLabelsWithoutTreatingArchiveAsSystemLabel() {
        assertEquals(EntityFolder.INBOX, GmailLabelFolder.getFolderType("INBOX", "SYSTEM"));
        assertEquals(EntityFolder.DRAFTS, GmailLabelFolder.getFolderType("DRAFT", "SYSTEM"));
        assertEquals(EntityFolder.SENT, GmailLabelFolder.getFolderType("SENT", "SYSTEM"));
        assertEquals(EntityFolder.TRASH, GmailLabelFolder.getFolderType("TRASH", "SYSTEM"));
        assertEquals(EntityFolder.JUNK, GmailLabelFolder.getFolderType("SPAM", "SYSTEM"));
        assertEquals(EntityFolder.USER, GmailLabelFolder.getFolderType("Label_123", "USER"));
        assertNull(GmailLabelFolder.getFolderType("ALL_MAIL", "SYSTEM"));
    }

    @Test
    public void systemLabelsUseLocalizedFolderResourcesAndCustomLabelsUseTheirDisplayNames() {
        assertEquals(R.string.title_folder_inbox,
                GmailLabelFolder.getSystemFolderDisplayResource(EntityFolder.INBOX));
        assertEquals(R.string.title_folder_drafts,
                GmailLabelFolder.getSystemFolderDisplayResource(EntityFolder.DRAFTS));
        assertEquals(R.string.title_folder_sent,
                GmailLabelFolder.getSystemFolderDisplayResource(EntityFolder.SENT));
        assertEquals(R.string.title_folder_trash,
                GmailLabelFolder.getSystemFolderDisplayResource(EntityFolder.TRASH));
        assertEquals(R.string.title_folder_junk,
                GmailLabelFolder.getSystemFolderDisplayResource(EntityFolder.JUNK));
        assertTrue(GmailLabelFolder.shouldLocalizeSystemDisplayName(null, "INBOX", "INBOX"));
        assertTrue(GmailLabelFolder.shouldLocalizeSystemDisplayName("INBOX", "INBOX", "INBOX"));
        assertFalse(GmailLabelFolder.shouldLocalizeSystemDisplayName("My Inbox", "INBOX", "INBOX"));

        GmailApi.Label custom = new GmailApi.Label("Label_123", "Project Receipts", "USER");
        assertEquals("Project Receipts",
                GmailLabelFolder.getFolderDisplayName(null, custom, EntityFolder.USER));
        assertEquals("__gmail_api_label__Label_123", GmailLabelFolder.getUserFolderName(custom.id));
        assertFalse(GmailLabelFolder.getFolderDisplayName(null, custom, EntityFolder.USER)
                .equals(GmailLabelFolder.getUserFolderName(custom.id)));
    }

    @Test
    public void existingAndNewUserFoldersUseApiNamesWithoutReplacingCustomTitlesOrIds() {
        String id = "Label_123";
        String localName = GmailLabelFolder.getUserFolderName(id);

        assertEquals("Notifications", GmailLabelFolder.getUserFolderDisplayName(
                null, id, localName, "Notifications"));
        assertEquals("Notifications", GmailLabelFolder.getUserFolderDisplayName(
                id, id, localName, "Notifications"));
        assertEquals("Notifications", GmailLabelFolder.getUserFolderDisplayName(
                localName, id, localName, "Notifications"));
        assertEquals("My custom title", GmailLabelFolder.getUserFolderDisplayName(
                "My custom title", id, localName, "Notifications"));
        assertEquals(localName, GmailLabelFolder.getUserFolderName(id));
        assertEquals("Notifications", GmailLabelFolder.getDisplayLabel(id,
                GmailLabelFolder.parseLabelDisplayData("Label_123:4E6F74696669636174696F6E73")));
        assertEquals(id, GmailLabelFolder.getDisplayLabel(id,
                GmailLabelFolder.parseLabelDisplayData(null)));
    }

    @Test
    public void labelDisplaySqlResolvesCurrentMembershipToGmailResourceName() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE message (id INTEGER PRIMARY KEY, account INTEGER, labels TEXT)");
            statement.execute("CREATE TABLE folder_view (account INTEGER, gmail_label_id TEXT, type TEXT, display TEXT)");
            statement.execute("INSERT INTO message (id, account, labels) VALUES (1, 7, 'INBOX Label_123')");
            statement.execute("INSERT INTO folder_view (account, gmail_label_id, type, display) " +
                    "VALUES (7, 'Label_123', '" + EntityFolder.USER + "', 'Notifications')");

            try (ResultSet rows = statement.executeQuery("SELECT " +
                    GmailLabelFolder.SQL_LABEL_DISPLAY_DATA + " AS names FROM message WHERE id = 1")) {
                assertTrue(rows.next());
                assertEquals("Notifications", GmailLabelFolder.getDisplayLabel("Label_123",
                        GmailLabelFolder.parseLabelDisplayData(rows.getString("names"))));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    public void oneMessageCanBelongToInboxSentAndSeveralUserLabelsAtOnce() {
        String[] labels = {"INBOX", "SENT", "Label_1", "Label_2"};

        assertTrue(GmailLabelFolder.isMember(labels, "INBOX"));
        assertTrue(GmailLabelFolder.isMember(labels, "SENT"));
        assertTrue(GmailLabelFolder.isMember(labels, "Label_1"));
        assertTrue(GmailLabelFolder.isMember(labels, "Label_2"));
        assertTrue(GmailLabelFolder.isMember(labels, GmailLabelFolder.ALL_MAIL_VIEW_ID));

    }

    @Test
    public void allMailIncludesNonSpamTrashLabelsAndTrashedMessagesAppearOnlyInTrash() {
        String[] labels = {"TRASH", "SENT", "Label_1"};

        assertTrue(GmailLabelFolder.isMember(labels, "TRASH"));
        assertFalse(GmailLabelFolder.isMember(labels, "SENT"));
        assertFalse(GmailLabelFolder.isMember(labels, "Label_1"));
        assertFalse(GmailLabelFolder.isMember(labels, GmailLabelFolder.ALL_MAIL_VIEW_ID));
    }

    @Test
    public void allMailViewIncludesInboxSentDraftsAndUserLabelsButExcludesSpamAndTrash() {
        String allMail = GmailLabelFolder.ALL_MAIL_VIEW_ID;

        assertTrue(GmailLabelFolder.isMember(new String[]{"INBOX", "SENT"}, allMail));
        assertTrue(GmailLabelFolder.isMember(new String[]{"DRAFT", "Label_1"}, allMail));
        assertFalse(GmailLabelFolder.isMember(new String[]{"SPAM", "Label_1"}, allMail));
        assertFalse(GmailLabelFolder.isMember(new String[]{"TRASH", "Label_1"}, allMail));
        assertFalse(GmailLabelFolder.isMember(null, "INBOX"));
    }

    @Test
    public void newAllMailViewIsVisibleAndSyncedButRediscoveryPreservesUserChoices() {
        EntityFolder created = new EntityFolder("All Mail", EntityFolder.ARCHIVE);
        created.synchronize = false;
        created.navigation = false;
        GmailLabelFolder.applyAllMailDefaults(created, true);
        assertTrue(created.synchronize);
        assertTrue(created.navigation);

        created.synchronize = false;
        created.navigation = false;
        GmailLabelFolder.applyAllMailDefaults(created, false);
        assertFalse(created.synchronize);
        assertFalse(created.navigation);
    }

    @Test
    public void membershipSqlUsesAccountScopedExactLabelTokensAndOneLocalMessageRow() {
        assertTrue(GmailLabelFolder.SQL_MATCH_MESSAGE_FOLDER.contains("message.account = folder.account"));
        assertTrue(GmailLabelFolder.SQL_MATCH_MESSAGE_F.contains("message.account = f.account"));
        assertTrue(GmailLabelFolder.SQL_MATCH_MESSAGE_FOLDER.contains("' ' || folder.gmail_label_id || ' '"));
        assertTrue(GmailLabelFolder.SQL_MESSAGE_IN_FOLDER.contains("message.folder = folder.id"));
        assertTrue(GmailLabelFolder.SQL_MESSAGE_IN_FOLDER.contains("OR " + GmailLabelFolder.SQL_MATCH_MESSAGE_FOLDER));
        assertTrue(GmailLabelFolder.getSystemLabelIds().containsAll(
                Arrays.asList("INBOX", "DRAFT", "SENT", "TRASH", "SPAM")));
        assertEquals("in:anywhere -in:spam -in:trash", GmailLabelFolder.ALL_MAIL_QUERY);
    }

    @Test
    public void oneLocalMessageRowFeedsMultipleLabelViewsAndTrashTakesPrecedence() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE message (id INTEGER PRIMARY KEY, account INTEGER NOT NULL, " +
                    "folder INTEGER, gmail_id TEXT, labels TEXT, zoho_id TEXT, zoho_folder_ids TEXT)");
            statement.execute("CREATE TABLE folder (id INTEGER PRIMARY KEY, account INTEGER NOT NULL, " +
                    "gmail_label_id TEXT, display TEXT, type TEXT, zoho_folder_id TEXT, notify INTEGER)");
            statement.execute("CREATE VIEW folder_view AS SELECT id, account, gmail_label_id, type FROM folder");
            statement.execute("INSERT INTO message (id, account, folder, gmail_id, labels) " +
                    "VALUES (1, 7, 1, 'native-A', 'INBOX SENT STARRED Label_42 CATEGORY_PERSONAL')");
            statement.execute("INSERT INTO folder (id, account, gmail_label_id, display, type) VALUES " +
                    "(1, 7, 'INBOX', 'Inbox', '" + EntityFolder.INBOX + "'), " +
                    "(2, 7, '" + GmailLabelFolder.ALL_MAIL_VIEW_ID + "', 'All Mail', '" + EntityFolder.ARCHIVE + "'), " +
                    "(3, 7, 'SENT', 'Sent', '" + EntityFolder.SENT + "'), " +
                    "(4, 7, 'SPAM', 'Spam', '" + EntityFolder.JUNK + "'), " +
                    "(5, 7, 'TRASH', 'Trash', '" + EntityFolder.TRASH + "'), " +
                    "(6, 7, 'Label_42', 'Project', '" + EntityFolder.USER + "'), " +
                    "(7, 7, 'Label_Target', 'Target', '" + EntityFolder.USER + "')");
            statement.execute("UPDATE folder SET notify = 1");

            List<String> memberships = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(
                    "SELECT message.id, folder.gmail_label_id FROM message, folder WHERE " +
                            GmailLabelFolder.SQL_MATCH_MESSAGE_FOLDER +
                            " ORDER BY folder.gmail_label_id")) {
                while (rows.next()) {
                    assertEquals(1, rows.getLong("id"));
                    memberships.add(rows.getString("gmail_label_id"));
                }
            }
            assertEquals(Arrays.asList("INBOX", "Label_42", "SENT", GmailLabelFolder.ALL_MAIL_VIEW_ID),
                    memberships);
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM message")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }

            statement.execute("UPDATE message SET labels = " +
                    "'SENT STARRED Label_42 CATEGORY_PERSONAL Label_Target' WHERE id = 1");
            memberships.clear();
            try (ResultSet rows = statement.executeQuery(
                    "SELECT folder.gmail_label_id FROM message, folder WHERE " +
                            GmailLabelFolder.SQL_MATCH_MESSAGE_FOLDER + " ORDER BY folder.gmail_label_id")) {
                while (rows.next())
                    memberships.add(rows.getString("gmail_label_id"));
            }
            assertEquals(Arrays.asList("Label_42", "Label_Target", "SENT", GmailLabelFolder.ALL_MAIL_VIEW_ID),
                    memberships);
            try (ResultSet rows = statement.executeQuery("SELECT labels FROM message WHERE id = 1")) {
                assertTrue(rows.next());
                assertEquals("SENT STARRED Label_42 CATEGORY_PERSONAL Label_Target", rows.getString("labels"));
            }

            statement.execute("UPDATE message SET labels = 'TRASH INBOX SENT SPAM Label_42 Label_Target' WHERE id = 1");
            try (ResultSet rows = statement.executeQuery("SELECT labels FROM message WHERE id = 1")) {
                assertTrue(rows.next());
                assertEquals("TRASH INBOX SENT SPAM Label_42 Label_Target", rows.getString("labels"));
                assertTrue(rows.getString("labels").contains("SENT"));
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM message, folder WHERE message.id = 1 AND folder.id = 1 AND " +
                            "folder.notify = 1 AND " + GmailLabelFolder.SQL_MESSAGE_IN_FOLDER)) {
                assertTrue(rows.next());
                assertEquals("TRASH-labelled Inbox row leaked into notifications", 0, rows.getInt(1));
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT folder.gmail_label_id FROM message, folder WHERE " +
                            GmailLabelFolder.SQL_MATCH_MESSAGE_FOLDER +
                            " ORDER BY folder.gmail_label_id")) {
                assertTrue(rows.next());
                assertEquals("TRASH", rows.getString("gmail_label_id"));
                assertFalse(rows.next());
            }

            for (int folderId : new int[]{1, 2, 3, 4, 6, 7}) {
                try (ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM message, folder WHERE message.id = 1 AND folder.id = " +
                                folderId + " AND " + GmailLabelFolder.SQL_MESSAGE_IN_FOLDER)) {
                    assertTrue(rows.next());
                    assertEquals("Non-Trash Gmail label view leaked a TRASH-labelled message " + folderId,
                            0, rows.getInt(1));
                }
                String targetMembership = GmailLabelFolder.SQL_MESSAGE_IN_TARGET_FOLDER
                        .replace(":folder", Integer.toString(folderId));
                try (ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM message, folder AS f WHERE message.id = 1 AND f.id = " +
                                folderId + " AND " + targetMembership)) {
                    assertTrue(rows.next());
                    assertEquals("Target-folder query leaked a TRASH-labelled message " + folderId,
                            0, rows.getInt(1));
                }
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM message, folder WHERE message.id = 1 AND folder.id = 5 AND " +
                            GmailLabelFolder.SQL_MESSAGE_IN_FOLDER)) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            String trashTarget = GmailLabelFolder.SQL_MESSAGE_IN_TARGET_FOLDER.replace(":folder", "5");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM message, folder AS f WHERE message.id = 1 AND f.id = 5 AND " +
                            trashTarget)) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }

            statement.execute("INSERT INTO message (id, account, folder, gmail_id, labels) " +
                    "VALUES (3, 7, 4, 'spam-A', 'SPAM INBOX SENT Label_42')");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM message, folder WHERE message.id = 3 AND folder.id = 2 AND " +
                            GmailLabelFolder.SQL_MESSAGE_IN_FOLDER)) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }

            // DaoFolder uses this LEFT JOIN plus COUNT(DISTINCT message.id); overlapping labels still
            // produce one local Gmail message row under the visible All Mail folder.
            statement.execute("UPDATE message SET labels = 'INBOX SENT Label_42' WHERE id = 1");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT folder.display, COUNT(DISTINCT message.id) AS messages FROM folder " +
                            "LEFT JOIN message ON " + GmailLabelFolder.SQL_MESSAGE_IN_FOLDER +
                            " WHERE folder.id = 2 GROUP BY folder.id")) {
                assertTrue(rows.next());
                assertEquals("All Mail", rows.getString("display"));
                assertEquals(1, rows.getInt("messages"));
                assertFalse(rows.next());
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM message WHERE account = 7 AND gmail_id = 'native-A'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }

            // SQL's Gmail-specific gate remains transparent to non-Gmail rows; Zoho still uses its own IDs.
            statement.execute("INSERT INTO message (id, account, folder, gmail_id, labels, zoho_id, zoho_folder_ids) " +
                    "VALUES (2, 8, 8, NULL, 'TRASH INBOX', 'zoho-A', '|zoho-trash|')");
            statement.execute("INSERT INTO folder (id, account, gmail_label_id, zoho_folder_id) " +
                    "VALUES (8, 8, NULL, 'zoho-trash')");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM message, folder WHERE message.id = 2 AND folder.id = 8 AND " +
                            GmailLabelFolder.SQL_MESSAGE_IN_FOLDER)) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }
}
