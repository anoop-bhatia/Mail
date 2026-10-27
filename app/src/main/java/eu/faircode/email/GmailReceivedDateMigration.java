package eu.faircode.email;

import androidx.sqlite.db.SupportSQLiteDatabase;

/** Adds the received-date marker and restarts only Gmail REST folder page cursors. */
final class GmailReceivedDateMigration {
    static final String[] SQL = new String[]{
            "ALTER TABLE `message` ADD COLUMN `gmail_received_checked` INTEGER NOT NULL DEFAULT 0",
            "UPDATE folder SET gmail_page_token = NULL WHERE account IN (" +
                    "SELECT id FROM account WHERE auth_type = " + ServiceAuthenticator.AUTH_TYPE_GMAIL + " OR (" +
                    "lower(host) IN ('imap.gmail.com', 'imap.googlemail.com', 'pop.gmail.com', " +
                    "'pop.googlemail.com', 'smtp.gmail.com', 'smtp.googlemail.com') AND " +
                    "(password LIKE '%\"version\":2,%' OR password LIKE '%\"version\": 2,%')))"
    };

    private GmailReceivedDateMigration() {
    }

    static void migrate(SupportSQLiteDatabase db) {
        for (String sql : SQL)
            db.execSQL(sql);
    }
}
