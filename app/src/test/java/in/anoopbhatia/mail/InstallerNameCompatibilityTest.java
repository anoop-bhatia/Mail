package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
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

/** Host-JavaCompiler tests for the API-gated installer lookup, using Android framework doubles. */
public class InstallerNameCompatibilityTest {
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
    public void api28DoesNotTouchPackageManagerOrLegacyApi() throws Exception {
        assertEquals(null, harness.invoke(28));
        assertEquals(0, harness.packageManagerCalls());
        assertEquals(0, harness.modernCalls());
        assertEquals(0, harness.legacyCalls());
    }

    @Test
    public void api29DoesNotTouchPackageManagerOrLegacyApi() throws Exception {
        assertEquals(null, harness.invoke(29));
        assertEquals(0, harness.packageManagerCalls());
        assertEquals(0, harness.modernCalls());
        assertEquals(0, harness.legacyCalls());
    }

    @Test
    public void api30ReportsModernInstallingPackage() throws Exception {
        harness.setInstallSource("com.example.store");
        assertEquals("com.example.store", harness.invoke(30));
        assertEquals(1, harness.packageManagerCalls());
        assertEquals(1, harness.modernCalls());
        assertEquals(0, harness.legacyCalls());
    }

    @Test
    public void api37ReportsModernInstallingPackage() throws Exception {
        harness.setInstallSource("com.example.store");
        assertEquals("com.example.store", harness.invoke(37));
        assertEquals(1, harness.packageManagerCalls());
        assertEquals(1, harness.modernCalls());
        assertEquals(0, harness.legacyCalls());
    }

    @Test
    public void nullInstallSourcePackageIsCachedAsNull() throws Exception {
        harness.setInstallSource(null);
        assertEquals(null, harness.invoke(30));
        assertEquals(null, harness.invoke(30));
        assertEquals(1, harness.packageManagerCalls());
        assertEquals(1, harness.modernCalls());
        assertEquals(0, harness.logCalls());
    }

    @Test
    public void successfulLookupIsCached() throws Exception {
        harness.setInstallSource("store");
        assertEquals("store", harness.invoke(30));
        harness.setInstallSource("different-store");
        assertEquals("store", harness.invoke(30));
        assertEquals(1, harness.packageManagerCalls());
        assertEquals(1, harness.modernCalls());
        assertEquals(0, harness.logCalls());
    }

    @Test
    public void failedLookupLogsAndCachesNull() throws Exception {
        harness.throwOnModernLookup(true);
        assertEquals(null, harness.invoke(30));
        harness.throwOnModernLookup(false);
        assertEquals(null, harness.invoke(30));
        assertEquals(1, harness.packageManagerCalls());
        assertEquals(1, harness.modernCalls());
        assertEquals(1, harness.logCalls());
    }

    @Test
    public void pre30NullCachePreventsLaterPackageManagerAccess() throws Exception {
        assertEquals(null, harness.invoke(29));
        harness.setInstallSource("store");
        assertEquals(null, harness.invoke(30));
        assertEquals(0, harness.packageManagerCalls());
        assertEquals(0, harness.modernCalls());
    }

    @Test
    public void sourceHasNoCompatHelperOrSuppressionAndDebugNullUsesDash() throws Exception {
        String helper = readProjectFile("app/src/main/java/in/anoopbhatia/mail/Helper.java");
        String method = extractMethod(helper, "static String getInstallerName(Context context)");
        assertTrue(method.contains("getInstallSourceInfo"));
        assertTrue(!method.contains("getInstallerPackageName"));
        assertTrue(!method.contains("getInstallerPackageNameCompat"));
        assertTrue(!method.contains("SuppressWarnings"));
        assertTrue(!helper.contains("getInstallerPackageNameCompat"));
        assertTrue(!helper.substring(helper.indexOf("static String getInstallerName(Context context)"),
                helper.indexOf("static long getInstallTime(Context context)")).contains("SuppressWarnings"));
        String debug = readProjectFile("app/src/main/java/in/anoopbhatia/mail/DebugHelper.java");
        assertTrue(debug.contains("installer == null ? \"-\" : installer"));
    }

    private static final class Harness implements AutoCloseable {
        private final Path root;
        private final URLClassLoader loader;
        private final Method invoke, packageManagerCalls, modernCalls, legacyCalls, logCalls;
        private final Method setInstallSource, throwOnModernLookup, reset;

        Harness() throws Exception {
            root = Files.createTempDirectory("installer-name-harness");
            writeSources(root);
            Path classes = root.resolve("classes");
            Files.createDirectories(classes);
            List<String> args = new ArrayList<>();
            args.add(Path.of(System.getProperty("java.home"), "bin", "javac").toString());
            args.add("-Xlint:deprecation");
            args.add("-Werror");
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
            Class<?> type = loader.loadClass("in.anoopbhatia.mail.InstallerNameHarness");
            invoke = type.getMethod("invoke", int.class);
            packageManagerCalls = type.getMethod("packageManagerCalls");
            modernCalls = type.getMethod("modernCalls");
            legacyCalls = type.getMethod("legacyCalls");
            logCalls = type.getMethod("logCalls");
            setInstallSource = type.getMethod("setInstallSource", String.class);
            throwOnModernLookup = type.getMethod("throwOnModernLookup", boolean.class);
            reset = type.getMethod("reset");
        }

        Object invoke(int sdk) throws Exception { return invoke.invoke(null, sdk); }
        int packageManagerCalls() throws Exception { return (Integer) packageManagerCalls.invoke(null); }
        int modernCalls() throws Exception { return (Integer) modernCalls.invoke(null); }
        int legacyCalls() throws Exception { return (Integer) legacyCalls.invoke(null); }
        int logCalls() throws Exception { return (Integer) logCalls.invoke(null); }
        void setInstallSource(String name) throws Exception { setInstallSource.invoke(null, name); }
        void throwOnModernLookup(boolean value) throws Exception { throwOnModernLookup.invoke(null, value); }
        void reset() throws Exception { reset.invoke(null); }

        @Override public void close() throws IOException {
            loader.close();
            delete(root);
        }
    }

    private static void writeSources(Path root) throws IOException {
        String production = readProjectFile("app/src/main/java/in/anoopbhatia/mail/Helper.java");
        String method = extractMethod(production, "static String getInstallerName(Context context)");
        write(root, "in/anoopbhatia/mail/InstallerNameHarness.java",
                "package in.anoopbhatia.mail;\n"
                        + "import android.content.Context; import android.content.pm.PackageManager; import android.os.Build; import android.util.Log;\n"
                        + "public class InstallerNameHarness {\n"
                        + " private static String installerName = \"?\";\n"
                        + " private static final Context context = new Context();\n"
                        + method + "\n"
                        + " public static Object invoke(int sdk){Build.VERSION.SDK_INT=sdk; return getInstallerName(context);}\n"
                        + " public static int packageManagerCalls(){return Context.packageManagerCalls;}\n"
                        + " public static int modernCalls(){return PackageManager.modernCalls;}\n"
                        + " public static int legacyCalls(){return PackageManager.legacyCalls;}\n"
                        + " public static int logCalls(){return android.util.Log.calls;}\n"
                        + " public static void setInstallSource(String s){PackageManager.installingPackage=s;}\n"
                        + " public static void throwOnModernLookup(boolean b){PackageManager.throwOnModern=b;}\n"
                        + " public static void reset(){installerName=\"?\"; Context.packageManagerCalls=0; PackageManager.modernCalls=0; PackageManager.legacyCalls=0; PackageManager.installingPackage=\"store\"; PackageManager.throwOnModern=false; android.util.Log.calls=0;}\n}\n");
        write(root, "android/os/Build.java", "package android.os; public final class Build { public static final class VERSION { public static int SDK_INT; } public static final class VERSION_CODES { public static final int R=30; } }\n");
        write(root, "android/content/Context.java", "package android.content; import android.content.pm.PackageManager; public class Context { public static int packageManagerCalls; public PackageManager getPackageManager(){packageManagerCalls++; return new PackageManager();} }\n");
        write(root, "android/content/pm/PackageManager.java", "package android.content.pm; public class PackageManager { public static int modernCalls, legacyCalls; public static String installingPackage=\"store\"; public static boolean throwOnModern; public InstallSourceInfo getInstallSourceInfo(String p){modernCalls++; if(throwOnModern) throw new RuntimeException(\"lookup failed\"); return new InstallSourceInfo(installingPackage);} @Deprecated public String getInstallerPackageName(String p){legacyCalls++; return \"legacy\";} public static class InstallSourceInfo { private final String name; public InstallSourceInfo(String n){name=n;} public String getInstallingPackageName(){return name;} } }\n");
        write(root, "android/util/Log.java", "package android.util; public class Log { public static int calls; public static void e(Throwable ex){calls++;} }\n");
        write(root, "in/anoopbhatia/mail/BuildConfig.java", "package in.anoopbhatia.mail; public final class BuildConfig { public static final String APPLICATION_ID=\"in.anoopbhatia.mail\"; }\n");
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
        int open = source.indexOf('{', start), depth = 0;
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
        try (var paths = Files.walk(path)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) { throw new RuntimeException(e); } });
        }
    }
}
