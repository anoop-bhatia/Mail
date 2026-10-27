package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Disassembles the actually resolved dependency: no reflective invocation or
 * restricted Room API is used. This is a pinned compatibility-branch source
 * contract, not a live Android/SQLite test. A Room upgrade requires review.
 */
public class RoomCallableContractTest {
    private static String bytecode;

    @BeforeClass
    public static void disassembleResolvedRoomClass() throws Exception {
        Path root = Files.createTempDirectory("room-callable-contract");
        try {
            Path type = root.resolve("RoomDatabase.class");
            try (InputStream input = RoomCallableContractTest.class.getResourceAsStream(
                    "/androidx/room/RoomDatabase.class")) {
                assertNotNull("resolved RoomDatabase resource", input);
                Files.copy(input, type);
            }
            Path output = root.resolve("javap.txt");
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "javap").toString(),
                    "-c", "-p", type.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Room javap timed out");
            }
            bytecode = Files.readString(output);
            assertEquals(bytecode, 0, process.exitValue());
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(file);
            }
        }
    }

    private static String method(String heading) {
        int start = bytecode.indexOf(heading);
        assertTrue("missing pinned Room method: " + heading, start >= 0);
        int end = bytecode.indexOf("\n  ", start + heading.length());
        // Instruction lines have at least four leading spaces, unlike headers.
        Matcher next = Pattern.compile("(?m)^  \\S.*[;{]$").matcher(bytecode);
        end = bytecode.length();
        while (next.find())
            if (next.start() > start) {
                end = next.start();
                break;
            }
        return bytecode.substring(start, end);
    }

    @Test
    public void publicCallableDelegatesWithoutWrappingOrCatching() {
        String body = method("public <V> V runInTransaction(java.util.concurrent.Callable<V>);");
        assertTrue(body.contains("Method runInTransaction:(Lkotlin/jvm/functions/Function0;)Ljava/lang/Object;"));
        assertTrue(body.contains("areturn"));
        assertFalse(body.contains("Exception table:"));
        assertFalse(Pattern.compile("\\bnew\\b|\\bathrow\\b").matcher(body).find());
    }

    @Test
    public void callableAdapterInvokesCallAndReturnsTheSameValueWithoutCatch() {
        Matcher heading = Pattern.compile("(?m)^  private static final java\\.lang\\.Object " +
                "runInTransaction\\$[^\\n]+\\(java\\.util\\.concurrent\\.Callable\\);").matcher(bytecode);
        assertTrue("pinned Callable adapter", heading.find());
        String body = method(heading.group().strip());
        assertTrue(body.contains("java/util/concurrent/Callable.call:()Ljava/lang/Object;"));
        assertTrue(body.contains("areturn"));
        assertFalse(body.contains("Exception table:"));
        assertFalse(Pattern.compile("\\bnew\\b|\\bathrow\\b").matcher(body).find());
    }

    @Test
    public void compatibilityRunnerRetainsExactBeginBodySuccessEndAndFinallyRethrow() {
        String body = method("private final <T> T runInTransaction(kotlin.jvm.functions.Function0<? extends T>);");
        int begin = body.indexOf("Method beginTransaction:()V");
        int callback = body.indexOf("Function0.invoke:()Ljava/lang/Object;");
        int success = body.indexOf("Method setTransactionSuccessful:()V");
        int end = body.indexOf("Method endTransaction:()V");
        assertTrue(begin >= 0 && begin < callback && callback < success && success < end);
        assertTrue(body.contains("30: areturn"));
        assertTrue(body.contains("31: astore_3"));
        assertTrue(body.contains("33: invokevirtual"));
        assertTrue(body.contains("36: aload_3"));
        assertTrue(body.contains("37: athrow"));
        // These exact catch-all entries implement finally; begin (offset 8)
        // is deliberately outside them. No checked-type wrapping catch exists.
        String table = body.substring(body.indexOf("Exception table:"));
        assertEquals("Exceptiontable:fromtotargettype112531any313231any",
                table.replaceAll("\\s+", ""));
        assertEquals(2, body.split("Method endTransaction:\\(\\)V", -1).length - 1);
        assertFalse(Pattern.compile("\\bnew\\b").matcher(body).find());
    }
}
