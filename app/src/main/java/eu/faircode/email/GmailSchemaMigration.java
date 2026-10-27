package eu.faircode.email;

import androidx.sqlite.db.SupportSQLiteDatabase;

/** Shared SQL for the backward-compatible Gmail-native message identity migration. */
final class GmailSchemaMigration {
    static final String[] SQL = new String[]{
            "ALTER TABLE `message` ADD COLUMN `gmail_id` TEXT",
            "ALTER TABLE `message` ADD COLUMN `gmail_thread_id` TEXT",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_account_gmail_id`" +
                    " ON `message` (`account`, `gmail_id`)"
    };

    private GmailSchemaMigration() {
    }

    static void migrate(SupportSQLiteDatabase db) {
        for (String statement : SQL)
            db.execSQL(statement);
    }
}
