package eu.faircode.email;

import androidx.sqlite.db.SupportSQLiteDatabase;

/** Additive local schema for Zoho Mail opaque IDs and bounded folder pages. */
final class ZohoSchemaMigration {
    static final String[] SQL = {
            "ALTER TABLE `folder` ADD COLUMN `zoho_folder_id` TEXT",
            "ALTER TABLE `folder` ADD COLUMN `zoho_page_start` INTEGER NOT NULL DEFAULT 1",
            "CREATE INDEX IF NOT EXISTS `index_folder_account_zoho_folder_id` " +
                    "ON `folder` (`account`, `zoho_folder_id`)",
            "ALTER TABLE `message` ADD COLUMN `zoho_id` TEXT",
            "ALTER TABLE `message` ADD COLUMN `zoho_folder_ids` TEXT",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_account_zoho_id` " +
                    "ON `message` (`account`, `zoho_id`)",
            "DROP VIEW IF EXISTS `folder_view`",
            "CREATE VIEW IF NOT EXISTS `folder_view` AS " + TupleFolderView.query
    };
    static final String[] RECENT_SCAN_SQL = {
            "ALTER TABLE `folder` ADD COLUMN `zoho_recent_page_start` INTEGER",
            "ALTER TABLE `folder` ADD COLUMN `zoho_recent_scan_head_id` TEXT",
            "ALTER TABLE `folder` ADD COLUMN `zoho_recent_boundary_id` TEXT"
    };

    private ZohoSchemaMigration() {
    }

    static void migrate(SupportSQLiteDatabase db) {
        for (String sql : SQL)
            db.execSQL(sql);
    }

    static void migrateRecentScan(SupportSQLiteDatabase db) {
        for (String sql : RECENT_SCAN_SQL)
            db.execSQL(sql);
    }
}
