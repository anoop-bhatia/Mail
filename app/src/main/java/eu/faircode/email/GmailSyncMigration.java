package eu.faircode.email;

import androidx.sqlite.db.SupportSQLiteDatabase;

final class GmailSyncMigration {
    static final String[] SQL = new String[]{
            "ALTER TABLE `account` ADD COLUMN `gmail_history_id` TEXT",
            "ALTER TABLE `account` ADD COLUMN `gmail_full_sync_history_id` TEXT",
            "ALTER TABLE `account` ADD COLUMN `gmail_full_sync_page_token` TEXT",
            "ALTER TABLE `account` ADD COLUMN `gmail_history_page_token` TEXT"
    };

    private GmailSyncMigration() {
    }

    static void migrate(SupportSQLiteDatabase db) {
        for (String sql : SQL)
            db.execSQL(sql);
    }
}
