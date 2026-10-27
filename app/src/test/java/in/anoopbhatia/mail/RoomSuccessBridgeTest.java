package in.anoopbhatia.mail;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Source contracts: Room owns the success marker; no DB bridge or consumer marker remains. */
public class RoomSuccessBridgeTest {
    @Test
    public void allProductionConsumersUseSupportedMarkerWithoutDbOverride() throws Exception {
        Path directory = projectFile("app/src/main/java/in/anoopbhatia/mail");
        try (var paths = Files.list(directory)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertFalse(file.toString(), source.matches("(?s).*\\bdb\\s*\\.\\s*setTransactionSuccessful\\s*\\(.*"));
            }
        }
        // Callback migrations legitimately remove markers and entire consumer files.
        // Guard the unsupported entry point, not a historical inventory total.
        assertFalse(readProjectFile("app/src/main/java/in/anoopbhatia/mail/DB.java")
                .contains("void setTransactionSuccessful()"));
        assertTrue(readProjectFile("app/src/main/java/in/anoopbhatia/mail/WorkerFts.java")
                .contains("sdb.setTransactionSuccessful();"));
    }

    @Test
    public void explicitMarkerBridgeStaysRemovedFromDb() throws Exception {
        String db = readProjectFile("app/src/main/java/in/anoopbhatia/mail/DB.java");
        assertFalse(db.contains("withTransactionLifecycle"));
        assertFalse(db.contains("this::beginTransaction"));
        assertFalse(db.contains("setTransactionSuccessful()"));
    }

    private static String readProjectFile(String relative) throws IOException {
        return Files.readString(projectFile(relative));
    }

    private static Path projectFile(String relative) throws IOException {
        for (Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath(); p != null; p = p.getParent()) {
            Path candidate = p.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IOException("cannot find project file " + relative);
    }
}
