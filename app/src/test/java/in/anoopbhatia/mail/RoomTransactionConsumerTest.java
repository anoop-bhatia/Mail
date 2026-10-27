package in.anoopbhatia.mail;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Source contracts for reviewed callback scopes, not an Android Room runtime test. */
public class RoomTransactionConsumerTest {
    private static final Pattern LEXICAL = Pattern.compile(
            "//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
    private static final Pattern CALLBACK = Pattern.compile(
            "\\bdb\\s*\\.\\s*(?:runInTransactionWithCleanup|runInTransaction|callInTransactionWithCleanup|callInTransaction|completeInTransactionWithCleanup|completeInTransaction)\\s*\\(\\s*\\(\\s*\\)\\s*->\\s*\\{");
    private static final Pattern LEGACY = Pattern.compile(
            "\\bdb\\s*\\.\\s*(?:beginTransaction|endTransaction|setTransactionSuccessful)\\s*\\(|" +
                    "\\bdb\\s*\\.\\s*getOpenHelper\\s*\\(\\s*\\)\\s*\\.\\s*getWritableDatabase\\s*\\(\\s*\\)\\s*\\.\\s*setTransactionSuccessful\\s*\\(");

    @Test
    public void reviewedConsumerBodiesRemainInSupportedCallbacks() throws Exception {
        // Anchors identify reviewed operations, not historical start/marker totals.
        // Additional conversions and reductions in legacy call counts remain valid.
        try (InputStream input = getClass().getResourceAsStream("/room-transaction-consumers.tsv")) {
            assertNotNull(input);
            String manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : manifest.split("\\R")) {
                if (line.isBlank() || line.startsWith("#"))
                    continue;
                String[] fields = line.split("\\t", 2);
                assertTrue("file and operation anchor: " + line, fields.length == 2);
                String source = Files.readString(sourceRoot().resolve(fields[0]));
                boolean found = false;
                for (String body : callbackBodies(source)) {
                    if (!compact(body).contains(fields[1]))
                        continue;
                    found = true;
                    assertFalse(fields[0] + ": callback retains a manual lifecycle call",
                            LEGACY.matcher(mask(body)).find());
                }
                assertTrue(fields[0] + ": reviewed operation is no longer callback-scoped: " + fields[1], found);
            }
        }
    }

    @Test
    public void callbackParserIgnoresBracesAndLifecycleTextInStringsAndComments() {
        String source = "db.runInTransactionWithCleanup(() -> { /* } */ " +
                "write(\"} db.beginTransaction();\"); if (ok) { write(\"{\"); } });";
        List<String> bodies = callbackBodies(source);
        assertTrue(bodies.size() == 1);
        assertTrue(bodies.get(0).contains("if (ok)"));
        assertFalse(LEGACY.matcher(mask(bodies.get(0))).find());
    }

    @Test
    public void callableParserCoversBothBeginFailureCleanupPolicies() {
        for (String helper : new String[]{"callInTransaction", "callInTransactionWithCleanup",
                "completeInTransaction", "completeInTransactionWithCleanup"}) {
            List<String> bodies = callbackBodies("return db." + helper +
                    "(() -> { write(\"}\"); return false; });");
            assertTrue(bodies.size() == 1);
            assertTrue(bodies.get(0).contains("return false;"));
        }
    }

    static List<String> callbackBodies(String source) {
        String masked = mask(source);
        Matcher matcher = CALLBACK.matcher(masked);
        List<String> bodies = new ArrayList<>();
        while (matcher.find()) {
            int start = matcher.end();
            int depth = 1;
            int end = start;
            while (end < masked.length() && depth > 0) {
                char ch = masked.charAt(end++);
                if (ch == '{') depth++;
                if (ch == '}') depth--;
            }
            assertTrue("unterminated transaction callback", depth == 0);
            bodies.add(source.substring(start, end - 1));
        }
        return bodies;
    }

    static String compact(String source) {
        Matcher matcher = LEXICAL.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String value = matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    value.startsWith("//") || value.startsWith("/*") ? "" : value));
        }
        matcher.appendTail(result);
        return result.toString().replaceAll("\\s+", "");
    }

    private static String mask(String source) {
        Matcher matcher = LEXICAL.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find())
            matcher.appendReplacement(result, Matcher.quoteReplacement(" ".repeat(matcher.group().length())));
        matcher.appendTail(result);
        return result.toString();
    }

    private static Path sourceRoot() {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return root;
    }
}
