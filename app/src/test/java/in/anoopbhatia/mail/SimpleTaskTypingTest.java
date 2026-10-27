package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Host source-contract checks only; Android lifecycle/executor behavior is not exercised here.
 */
public class SimpleTaskTypingTest {
    @Test
    public void taskCollectionsAndObservedReferencesAreParameterized() throws Exception {
        String simpleTask = source("app/src/main/java/in/anoopbhatia/mail/SimpleTask.java");
        String activityView = source("app/src/main/java/in/anoopbhatia/mail/ActivityView.java");
        String adapterMessage = source("app/src/main/java/in/anoopbhatia/mail/AdapterMessage.java");
        String debugHelper = source("app/src/main/java/in/anoopbhatia/mail/DebugHelper.java");
        String fragmentAccount = source("app/src/main/java/in/anoopbhatia/mail/FragmentAccount.java");

        assertTrue(simpleTask.contains("List<SimpleTask<?>> tasks"));
        assertTrue(simpleTask.contains("static List<SimpleTask<?>> getList()"));
        assertFalse(activityView.contains("final SimpleTask move"));
        assertFalse(activityView.contains("final SimpleTask show"));
        assertTrue(adapterMessage.contains("private SimpleTask<?> taskContactInfo;"));
        assertTrue(debugHelper.contains("for (SimpleTask<?> task : SimpleTask.getList())"));
        assertTrue(fragmentAccount.contains("final SimpleTask<List<EntityFolder>> task"));
    }

    private static String source(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path))
            path = Paths.get(System.getProperty("user.dir"), relative.substring("app/".length()));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
