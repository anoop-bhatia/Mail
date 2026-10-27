package eu.faircode.email;

import androidx.sqlite.db.SupportSQLiteDatabase;

/** Additive schema changes for the native Microsoft Graph mail provider. */
final class GraphSchemaMigration {
    static final String[] SQL = new String[]{
            "ALTER TABLE `folder` ADD COLUMN `graph_folder_id` TEXT",
            "ALTER TABLE `folder` ADD COLUMN `graph_page_link` TEXT",
            "ALTER TABLE `folder` ADD COLUMN `graph_delta_link` TEXT",
            "CREATE INDEX IF NOT EXISTS `index_folder_account_graph_folder_id` " +
                    "ON `folder` (`account`, `graph_folder_id`)",
            "ALTER TABLE `message` ADD COLUMN `graph_id` TEXT",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_account_graph_id` " +
                    "ON `message` (`account`, `graph_id`)"
    };

    private GraphSchemaMigration() {
    }

    static void migrate(SupportSQLiteDatabase db) {
        for (String sql : SQL)
            db.execSQL(sql);
    }
}
