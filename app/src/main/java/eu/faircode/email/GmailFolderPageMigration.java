package eu.faircode.email;

import androidx.sqlite.db.SupportSQLiteDatabase;

/** Adds Gmail's user-requested per-folder page token and resumable history batch. */
final class GmailFolderPageMigration {
    static final String[] SQL = new String[]{
            "ALTER TABLE folder ADD COLUMN gmail_page_token TEXT",
            "ALTER TABLE account ADD COLUMN gmail_history_pending TEXT"
    };

    private GmailFolderPageMigration() {
    }

    static void migrate(SupportSQLiteDatabase db) {
        for (String sql : SQL)
            db.execSQL(sql);
    }
}
