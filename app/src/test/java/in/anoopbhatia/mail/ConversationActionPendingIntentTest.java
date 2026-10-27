package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Framework overload contract; Android's local JVM stubs cannot send intents. */
public class ConversationActionPendingIntentTest {
    @Test
    public void conversationActionUsesOptionsOverloadAvailableBeforeApi34() throws Exception {
        String source = Files.readString(
                Paths.get("src/main/java/in/anoopbhatia/mail/AdapterMessage.java"),
                StandardCharsets.UTF_8);
        String compact = source.replaceAll("\\s+", " ");

        assertTrue("Keep launch options while using the API 23 overload",
                compact.contains("raction.getActionIntent().send(context, 0, null, null, null, null, Helper.getBackgroundActivityOptions());"));
        assertFalse("The Bundle-only overload requires API 34",
                source.contains("raction.getActionIntent().send(Helper.getBackgroundActivityOptions())"));
    }
}
