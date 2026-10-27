package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Executes the extracted DB override with host doubles, not Android Room/SQLite. */
public class RoomEndBridgeTest {
    private static Harness harness;
    private static final String COMPLETE = "open,writable,end,check,tracker,refresh";
    private static final String[] POINTS = {"open", "writable", "end", "check", "tracker", "refresh"};

    @BeforeClass public static void compileHarness() throws Exception { harness = new Harness(); }
    @AfterClass public static void closeHarness() throws Exception { if (harness != null) harness.close(); }

    @Test public void outermostEndPreservesExactPinnedRoomOrder() throws Exception {
        compare(false, "", null);
        assertEquals(COMPLETE, harness.trace());
        assertEquals(1, harness.count("end"));
        assertEquals(1, harness.count("refresh"));
        assertEquals(0, harness.parentCalls());
    }

    @Test public void nestedEndDoesNotRefresh() throws Exception {
        compare(true, "", null);
        assertEquals("open,writable,end,check", harness.trace());
        assertEquals(0, harness.count("tracker"));
        assertEquals(0, harness.count("refresh"));
    }

    @Test public void runtimeAndErrorFailuresKeepIdentityAndShortCircuitAtEveryStage() throws Exception {
        for (String point : POINTS)
            for (Throwable failure : new Throwable[]{new IllegalStateException("unknown"), new AssertionError("unknown")}) {
                compare(false, point, failure);
                assertSame(failure, harness.thrown());
                assertNull(harness.logged());
                int end = COMPLETE.indexOf(point) + point.length();
                assertEquals(COMPLETE.substring(0, end), harness.trace());
            }
    }

    @Test public void bothKnownDeviceMessagesAreLoggedAndSwallowedAtEveryStage() throws Exception {
        for (String point : POINTS)
            for (String message : new String[]{"no current transaction", "no transaction is active"})
                for (Throwable failure : new Throwable[]{new IllegalStateException("prefix " + message + " suffix"), new AssertionError(message)}) {
                    compare(false, point, failure);
                    assertNull(harness.thrown());
                    assertSame(failure, harness.logged());
                    assertEquals(point.equals("refresh") ? 1 : 0, harness.count("refresh"));
                }
    }

    @Test public void nullEmptyUnknownAndCaseChangedMessagesStillEscapeUnmodified() throws Exception {
        for (String point : POINTS)
            for (String message : new String[]{null, "", "unknown", "No current transaction", "No transaction is active"})
                for (Throwable failure : new Throwable[]{new IllegalStateException(message), new AssertionError(message)}) {
                    compare(false, point, failure);
                    assertSame(failure, harness.thrown());
                    assertNull(harness.logged());
                }
    }

    @Test public void nestedEndFailureDoesNotAttemptRefresh() throws Exception {
        Throwable failure = new IllegalStateException("unknown");
        compare(true, "end", failure);
        assertSame(failure, harness.thrown());
        assertEquals("open,writable,end", harness.trace());
    }

    @Test public void generatedAndPagingStyleBaseDispatchStillUsesDbOverride() throws Exception {
        for (String entry : new String[]{"generated", "paging"}) {
            harness.dispatch(entry);
            assertEquals(COMPLETE, harness.trace());
            assertEquals(0, harness.parentCalls());
        }
    }

    @Test public void repeatedCallsReacquireCurrentDatabaseAndTracker() throws Exception {
        harness.repeatWithNewReceivers();
        assertEquals(COMPLETE + "," + COMPLETE, harness.trace());
        assertEquals(2, harness.count("open"));
        assertEquals(2, harness.count("writable"));
        assertEquals(2, harness.count("tracker"));
        assertEquals(2, harness.count("refresh"));
        assertEquals("1,2", harness.receivers());
    }

    @Test public void productionKeepsInheritedTransactionCheckAndOriginalRefreshApi() throws Exception {
        String method = productionMethod();
        assertTrue(method.contains("if (!inTransaction())"));
        assertTrue(method.contains("getInvalidationTracker().refreshVersionsAsync();"));
        assertTrue(!method.contains("refreshAsync()"));
        assertTrue(!method.contains("super.endTransaction();"));
    }

    private static void compare(boolean nested, String point, Throwable failure) throws Exception {
        harness.execute(true, nested, point, failure);
        String trace = harness.trace();
        Throwable thrown = harness.thrown(), logged = harness.logged();
        harness.execute(false, nested, point, failure);
        assertEquals(trace, harness.trace());
        assertSame(thrown, harness.thrown());
        assertSame(logged, harness.logged());
    }

    private static String productionMethod() throws IOException {
        String source = Files.readString(projectFile("app/src/main/java/in/anoopbhatia/mail/DB.java"));
        int declaration = source.indexOf("public void endTransaction()");
        // Only the end bridge is overridden; begin uses Room's inherited implementation.
        int start = source.lastIndexOf("    @Override", declaration);
        int end = source.indexOf("    public static class Converters", declaration);
        if (start < 0 || end < 0) throw new IOException("cannot extract DB.endTransaction");
        return source.substring(start, end);
    }

    private static final class Harness implements AutoCloseable {
        final Path root;
        final URLClassLoader loader;
        final Class<?> type;

        Harness() throws Exception {
            Path made = Files.createTempDirectory("room-end-bridge-harness");
            URLClassLoader opened = null;
            try {
                String method = productionMethod();
                String body = """
                        // Preserve Room 2.8.5's end lifecycle, including outermost invalidation.
                                    getOpenHelper().getWritableDatabase().endTransaction();
                                    if (!inTransaction())
                                        getInvalidationTracker().refreshVersionsAsync();
                        """.strip();
                if (!method.contains(body)) throw new IOException("production delegate changed; review equivalence");
                String legacy = method.replace(body, "super.endTransaction();");
                write(made, "in/anoopbhatia/mail/RoomEndHarness.java", TEMPLATE.formatted(method, legacy));
                write(made, "in/anoopbhatia/mail/TextUtils.java", "package in.anoopbhatia.mail; class TextUtils { static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; } }\n");
                write(made, "in/anoopbhatia/mail/Log.java", "package in.anoopbhatia.mail; class Log { static void w(Throwable t) { RoomEndHarness.logged = t; } }\n");
                Path classes = made.resolve("classes"); Files.createDirectories(classes);
                List<String> args = new ArrayList<>();
                args.add(Path.of(System.getProperty("java.home"), "bin", "javac").toString());
                args.add("-d"); args.add(classes.toString());
                try (var paths = Files.walk(made)) {
                    paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
                }
                Path log = made.resolve("javac.log");
                Process compiler = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                if (!compiler.waitFor(30, TimeUnit.SECONDS)) {
                    compiler.destroyForcibly(); compiler.waitFor(5, TimeUnit.SECONDS); fail("harness javac timed out");
                }
                assertEquals(Files.readString(log, StandardCharsets.UTF_8), 0, compiler.exitValue());
                opened = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null);
                type = opened.loadClass("in.anoopbhatia.mail.RoomEndHarness"); root = made; loader = opened;
            } catch (Throwable ex) {
                if (opened != null) opened.close(); delete(made);
                if (ex instanceof Exception) throw (Exception) ex;
                if (ex instanceof Error) throw (Error) ex;
                throw new AssertionError(ex);
            }
        }
        Object call(String name, Class<?>[] types, Object... args) throws Exception { return type.getMethod(name, types).invoke(null, args); }
        Object value(String name) throws Exception { return call(name, new Class<?>[0]); }
        void execute(boolean old, boolean nested, String point, Throwable failure) throws Exception {
            call("execute", new Class<?>[]{boolean.class, boolean.class, String.class, Throwable.class}, old, nested, point, failure);
        }
        void dispatch(String entry) throws Exception { call("dispatch", new Class<?>[]{String.class}, entry); }
        void repeatWithNewReceivers() throws Exception { value("repeatWithNewReceivers"); }
        String trace() throws Exception { return (String) value("trace"); }
        String receivers() throws Exception { return (String) value("receivers"); }
        Throwable thrown() throws Exception { return (Throwable) value("thrown"); }
        Throwable logged() throws Exception { return (Throwable) value("logged"); }
        int parentCalls() throws Exception { return (Integer) value("parentCalls"); }
        int count(String point) throws Exception { return (Integer) call("count", new Class<?>[]{String.class}, point); }
        public void close() throws IOException { try { loader.close(); } finally { delete(root); } }
    }

    private static final String TEMPLATE = """
            package in.anoopbhatia.mail;
            import java.util.*;
            public class RoomEndHarness {
                static List<String> trace = new ArrayList<>(), receivers = new ArrayList<>();
                static Throwable failure, thrown, logged;
                static String point;
                static boolean nested;
                static int parentCalls;
                static D current;
                static I tracker;
                static void step(String name) { trace.add(name); if (name.equals(point)) rethrow(failure); }
                static void rethrow(Throwable t) { if (t instanceof RuntimeException) throw (RuntimeException)t; if (t instanceof Error) throw (Error)t; throw new AssertionError(t); }
                public static class D {
                    final int id;
                    D(int id) { this.id = id; }
                    public void endTransaction() { receivers.add(Integer.toString(id)); step("end"); }
                }
                public static class H { public D getWritableDatabase() { step("writable"); return current; } }
                public static class I { public void refreshVersionsAsync() { step("refresh"); } }
                public static class P {
                    public H getOpenHelper() { step("open"); return new H(); }
                    // Model only Room's return/exception boundary: production still invokes
                    // the real inherited inTransaction(), with all its open/helper checks.
                    public boolean inTransaction() { step("check"); return nested; }
                    public I getInvalidationTracker() { step("tracker"); return tracker; }
                    public void endTransaction() {
                        parentCalls++;
                        // Exact pinned Room 2.8.5 internalEndTransaction body.
                        getOpenHelper().getWritableDatabase().endTransaction();
                        if (!inTransaction()) getInvalidationTracker().refreshVersionsAsync();
                    }
                }
                public static class DB extends P { %s }
                public static class LegacyDB extends P { %s }
                static void reset(boolean n, String p, Throwable f) {
                    trace.clear(); receivers.clear(); failure=f; thrown=logged=null; point=p; nested=n;
                    parentCalls=0; current=new D(1); tracker=new I();
                }
                public static void execute(boolean old, boolean n, String p, Throwable f) {
                    reset(n,p,f);
                    try { (old ? new LegacyDB() : new DB()).endTransaction(); } catch (Throwable t) { thrown=t; }
                }
                static class Paging { void close(P p) { p.endTransaction(); } }
                public static void dispatch(String entry) {
                    reset(false,"",null); P db=new DB();
                    if (entry.equals("paging")) new Paging().close(db); else db.endTransaction();
                }
                public static void repeatWithNewReceivers() {
                    reset(false,"",null); P db=new DB(); db.endTransaction();
                    current=new D(2); tracker=new I(); db.endTransaction();
                }
                public static String trace() { return String.join(",",trace); }
                public static String receivers() { return String.join(",",receivers); }
                public static Throwable thrown() { return thrown; }
                public static Throwable logged() { return logged; }
                public static int parentCalls() { return parentCalls; }
                public static int count(String name) { return Collections.frequency(trace,name); }
            }
            """;

    private static Path projectFile(String relative) throws IOException {
        for (Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath(); p != null; p = p.getParent()) {
            Path candidate = p.resolve(relative); if (Files.exists(candidate)) return candidate;
        }
        throw new IOException(relative);
    }
    private static void write(Path root, String name, String content) throws IOException {
        Path path = root.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, content);
    }
    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(path);
        }
    }
}
