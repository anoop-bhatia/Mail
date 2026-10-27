package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
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

/**
 * Executes the production method against host-JavaCompiler framework doubles, not Android runtime.
 * The doubles model Android 13's default pending-intent BAL value and reject deprecated use.
 */
public class BackgroundActivityOptionsTest {
    private static Harness harness;

    @BeforeClass
    public static void compileHarness() throws Exception {
        harness = new Harness();
    }

    @AfterClass
    public static void closeHarness() throws Exception {
        if (harness != null) harness.close();
    }

    @org.junit.Before
    public void resetDoubleState() throws Exception {
        harness.reset();
    }

    @Test
    public void api28ReturnsNullWithoutCreatingOptions() throws Exception {
        assertNull(harness.invoke(28));
        assertEquals(0, harness.makeBasicCalls());
    }

    @Test
    public void api29ReturnsNullWithoutCreatingOptions() throws Exception {
        assertNull(harness.invoke(29));
        assertEquals(0, harness.makeBasicCalls());
    }

    @Test
    public void api32ReturnsNullWithoutCreatingOptions() throws Exception {
        assertNull(harness.invoke(32));
        assertEquals(0, harness.makeBasicCalls());
    }

    @Test
    public void api33UsesDefaultTrueAndNeverCallsDeprecatedSetter() throws Exception {
        Object bundle = harness.invoke(33);
        assertNotNull(bundle);
        assertTrue(harness.pendingIntentBalAllowed(bundle));
        assertEquals(0, harness.deprecatedSetterCalls());
        assertEquals(0, harness.modeSetterCalls());
    }

    @Test
    public void api34UsesLegacyAllowedMode() throws Exception {
        Object bundle = harness.invoke(34);
        assertNotNull(bundle);
        assertEquals(1, harness.mode(bundle));
        assertEquals(0, harness.deprecatedSetterCalls());
        assertEquals(1, harness.modeSetterCalls());
    }

    @Test
    public void api35UsesLegacyAllowedMode() throws Exception {
        Object bundle = harness.invoke(35);
        assertNotNull(bundle);
        assertEquals(1, harness.mode(bundle));
        assertEquals(0, harness.deprecatedSetterCalls());
        assertEquals(1, harness.modeSetterCalls());
    }

    @Test
    public void api36UsesAlwaysMode() throws Exception {
        Object bundle = harness.invoke(36);
        assertNotNull(bundle);
        assertEquals(3, harness.mode(bundle));
        assertEquals(0, harness.deprecatedSetterCalls());
        assertEquals(1, harness.modeSetterCalls());
    }

    @Test
    public void api37UsesAlwaysMode() throws Exception {
        Object bundle = harness.invoke(37);
        assertNotNull(bundle);
        assertEquals(3, harness.mode(bundle));
        assertEquals(0, harness.deprecatedSetterCalls());
        assertEquals(1, harness.modeSetterCalls());
    }

    @Test
    public void eachInvocationUsesFreshBuilderAndReturnedBundle() throws Exception {
        Object first = harness.invoke(33);
        Object second = harness.invoke(33);
        assertTrue("each call must return a fresh bundle", first != second);
        assertEquals(2, harness.makeBasicCalls());
        assertSame(first, harness.lastBundle(0));
        assertSame(second, harness.lastBundle(1));
    }

    private static final class Harness implements AutoCloseable {
        private final Path root;
        private final URLClassLoader loader;
        private final Method invoke;
        private final Method makeBasicCalls;
        private final Method deprecatedSetterCalls;
        private final Method modeSetterCalls;
        private final Method pendingIntentBalAllowed;
        private final Method mode;
        private final Method lastBundle;
        private final Method reset;

        Harness() throws Exception {
            root = Files.createTempDirectory("background-activity-options-harness");
            writeSources(root);
            Path classes = root.resolve("classes");
            Files.createDirectories(classes);
            List<String> args = new ArrayList<>();
            args.add(Path.of(System.getProperty("java.home"), "bin", "javac").toString());
            args.add("-d");
            args.add(classes.toString());
            try (var paths = Files.walk(root)) {
                paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
            }
            Path compilerLog = root.resolve("javac.log");
            Process compiler = new ProcessBuilder(args).redirectErrorStream(true)
                    .redirectOutput(compilerLog.toFile()).start();
            boolean finished = compiler.waitFor(30, TimeUnit.SECONDS);
            if (!finished) compiler.destroyForcibly();
            int exitCode = finished ? compiler.exitValue() : -1;
            String diagnostics = Files.readString(compilerLog, StandardCharsets.UTF_8);
            assertTrue("temporary framework harness timed out: " + diagnostics, finished);
            assertEquals("temporary framework harness failed to compile: " + diagnostics, 0, exitCode);
            loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null);
            Class<?> type = loader.loadClass("in.anoopbhatia.mail.BackgroundActivityOptionsHarness");
            invoke = type.getMethod("invoke", int.class);
            makeBasicCalls = type.getMethod("makeBasicCalls");
            deprecatedSetterCalls = type.getMethod("deprecatedSetterCalls");
            modeSetterCalls = type.getMethod("modeSetterCalls");
            pendingIntentBalAllowed = type.getMethod("pendingIntentBalAllowed", Object.class);
            mode = type.getMethod("mode", Object.class);
            lastBundle = type.getMethod("lastBundle", int.class);
            reset = type.getMethod("reset");
        }

        Object invoke(int sdk) throws Exception { return invoke.invoke(null, sdk); }
        int makeBasicCalls() throws Exception { return (Integer) makeBasicCalls.invoke(null); }
        int deprecatedSetterCalls() throws Exception { return (Integer) deprecatedSetterCalls.invoke(null); }
        int modeSetterCalls() throws Exception { return (Integer) modeSetterCalls.invoke(null); }
        boolean pendingIntentBalAllowed(Object bundle) throws Exception { return (Boolean) pendingIntentBalAllowed.invoke(null, bundle); }
        int mode(Object bundle) throws Exception { return (Integer) mode.invoke(null, bundle); }
        Object lastBundle(int index) throws Exception { return lastBundle.invoke(null, index); }
        void reset() throws Exception { reset.invoke(null); }

        @Override public void close() throws IOException {
            loader.close();
            delete(root);
        }
    }

    private static void writeSources(Path root) throws IOException {
        String production = readProjectFile("app/src/main/java/in/anoopbhatia/mail/Helper.java");
        String method = extractMethod(production, "static Bundle getBackgroundActivityOptions()");
        write(root, "in/anoopbhatia/mail/BackgroundActivityOptionsHarness.java",
                "package in.anoopbhatia.mail;\n"
                        + "import android.app.ActivityOptions; import android.os.Build; import android.os.Bundle;\n"
                        + "import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;\n"
                        + "import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS;\n"
                        + "public class BackgroundActivityOptionsHarness {\n" + method + "\n"
                        + " public static Object invoke(int sdk) { Build.VERSION.SDK_INT=sdk; return getBackgroundActivityOptions(); }\n"
                        + " public static int makeBasicCalls(){return ActivityOptions.makeBasicCalls;}\n"
                        + " public static int deprecatedSetterCalls(){return ActivityOptions.deprecatedSetterCalls;}\n"
                        + " public static int modeSetterCalls(){return ActivityOptions.modeSetterCalls;}\n"
                        + " public static boolean pendingIntentBalAllowed(Object b){return ((Bundle)b).pendingIntentBalAllowed;}\n"
                        + " public static int mode(Object b){return ((Bundle)b).mode;}\n"
                        + " public static Object lastBundle(int i){return ActivityOptions.bundles[i];}\n"
                        + " public static void reset(){ActivityOptions.makeBasicCalls=0; ActivityOptions.deprecatedSetterCalls=0; ActivityOptions.modeSetterCalls=0; ActivityOptions.bundles=new Bundle[10];}\n}\n");
        write(root, "android/os/Build.java", "package android.os; public final class Build { public static final class VERSION { public static int SDK_INT; } public static final class VERSION_CODES { public static final int TIRAMISU=33, UPSIDE_DOWN_CAKE=34, BAKLAVA=36; } }\n");
        write(root, "android/os/Bundle.java", "package android.os; public class Bundle { public final boolean pendingIntentBalAllowed; public final int mode; public Bundle(boolean allowed,int mode){this.pendingIntentBalAllowed=allowed;this.mode=mode;} }\n");
        write(root, "android/app/ActivityOptions.java", "package android.app; import android.os.Bundle; public class ActivityOptions { public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOWED=1, MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS=3; public static int makeBasicCalls, deprecatedSetterCalls, modeSetterCalls; public static Bundle[] bundles=new Bundle[10]; private boolean allowed=true; private int mode; public static ActivityOptions makeBasic(){makeBasicCalls++; return new ActivityOptions();} public void setPendingIntentBackgroundActivityLaunchAllowed(boolean value){deprecatedSetterCalls++; throw new AssertionError(\"deprecated API 33 setter must not be called\");} public void setPendingIntentBackgroundActivityStartMode(int value){if(android.os.Build.VERSION.SDK_INT<34) throw new AssertionError(\"too-new mode API\"); if(value==MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS && android.os.Build.VERSION.SDK_INT<36) throw new AssertionError(\"too-new allow-always API\"); modeSetterCalls++; mode=value;} public Bundle toBundle(){Bundle b=new Bundle(allowed,mode); bundles[makeBasicCalls-1]=b; return b;} }\n");
    }

    private static String readProjectFile(String relative) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = current; p != null; p = p.getParent()) {
            Path candidate = p.resolve(relative);
            if (Files.exists(candidate)) return Files.readString(candidate);
        }
        throw new IOException("cannot find project file " + relative);
    }

    private static String extractMethod(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new IllegalArgumentException("missing method " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new IllegalArgumentException("unclosed method " + signature);
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file, content);
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) { paths.sorted((a,b) -> b.compareTo(a)).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) { throw new RuntimeException(e); } }); }
    }
}
