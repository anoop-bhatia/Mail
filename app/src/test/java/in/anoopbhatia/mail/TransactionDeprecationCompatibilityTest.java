package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the intentionally retained DB transaction bridge.
 *
 * Room 2.8.5 deprecates RoomDatabase's three-phase transaction methods in
 * favor of callback-scoped runInTransaction. The application has many
 * callers whose transaction spans existing try/finally blocks, so this test
 * makes sure a DB-only sweep does not silently replace the bridge with a
 * driver-only API or an unsupported SQL BEGIN/COMMIT implementation.
 */
public class TransactionDeprecationCompatibilityTest {
    @Test
    public void dbBridgeKeepsRoomTransactionLifecycleAndLegacyErrorHandling() throws Exception {
        String source = source("DB");
        // Room's inherited beginTransaction() is used directly again: the override only
        // called super and added a deprecation warning without a hook of its own.
        assertFalse(source.contains("public void beginTransaction()"));
        assertFalse(source.contains("super.beginTransaction();"));
        // Consumers now use the supported SQLite marker directly; Room's inherited
        // equivalent remains available to generated DAO/paging transaction code.
        assertFalse(source.contains("public void setTransactionSuccessful()"));
        assertFalse(source.contains("getOpenHelper().getWritableDatabase().setTransactionSuccessful();"));
        assertFalse(source.contains("super.setTransactionSuccessful();"));
        assertTrue(source.contains("public void endTransaction()"));
        assertFalse(source.contains("super.endTransaction();"));
        assertTrue(source.contains("getOpenHelper().getWritableDatabase().endTransaction();"));
        assertTrue(source.contains("if (!inTransaction())"));
        assertTrue(source.contains("getInvalidationTracker().refreshVersionsAsync();"));
        // Existing callers rely on Throwable (rather than Exception) escaping
        // the three-phase bridge, with Room/SQLite deciding rollback when the
        // success marker was not reached.
        assertTrue(source.contains("catch (Throwable ex)"));
        assertTrue(source.contains("if (TextUtils.isEmpty(msg))"));
        assertTrue(source.contains("throw ex;"));
        assertTrue(source.contains("no current transaction"));
        assertTrue(source.contains("no transaction is active"));
        assertTrue(source.contains("Log.w(ex);"));
    }

    @Test
    public void supportedCallbackTransactionApiRemainsAvailableAndUsedBySafeSync() throws Exception {
        DB.class.getMethod("runInTransaction", Runnable.class);
        assertTrue(source("GraphMessageSync").contains("db.runInTransaction(() ->"));
        assertTrue(source("ZohoMessageSync").contains("db.runInTransaction(() ->"));
        assertTrue(source("NativeReaderState").contains("db.runInTransaction(() ->"));
        assertTrue(source("NativeFolderState").contains("db.runInTransaction(() ->"));
        assertTrue(source("RepairHelper").contains("db.runInTransaction(() ->"));
    }
    @Test
    public void dbBridgeDoesNotPretendSupportSQLiteIsDriverMode() throws Exception {
        String source = source("DB");
        assertFalse(source.contains("useWriterConnection"));
        assertFalse(source.contains("getDriver()"));
        assertFalse(source.contains("execSQL(\"BEGIN"));
        assertFalse(source.contains("execSQL(\"COMMIT"));
        assertFalse(source.contains("execSQL(\"ROLLBACK"));
    }

    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve(name + ".java"));
    }
}
