package eu.faircode.email;

import androidx.sqlite.db.SupportSQLiteDatabase;

final class GmailLabelFolderMigration {
    // Version 303 predates Zoho columns; migration 306→307 rebuilds this view again.
    private static final String LEGACY_FOLDER_VIEW_QUERY =
            "SELECT id, account, name, type, inherited_type, display, color, unified, notify, read_only, gmail_label_id FROM folder";

    static final String[] SQL = new String[]{
            "ALTER TABLE `folder` ADD COLUMN `gmail_label_id` TEXT",
            "CREATE INDEX IF NOT EXISTS `index_folder_account_gmail_label_id` ON `folder` (`account`, `gmail_label_id`)",
            "DROP VIEW IF EXISTS `folder_view`",
            "CREATE VIEW IF NOT EXISTS `folder_view` AS " + LEGACY_FOLDER_VIEW_QUERY
    };

    private GmailLabelFolderMigration() {
    }

    static void migrate(SupportSQLiteDatabase db) {
        for (String sql : SQL)
            db.execSQL(sql);
    }
}
