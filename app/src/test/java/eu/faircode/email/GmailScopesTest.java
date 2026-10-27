package eu.faircode.email;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailScopesTest {
    @Test
    public void requestedScopesIncludePermanentDeleteAndPreserveReadModifyAndSend() {
        assertEquals(Arrays.asList(
                        "https://www.googleapis.com/auth/gmail.readonly",
                        "https://www.googleapis.com/auth/gmail.modify",
                        "https://www.googleapis.com/auth/gmail.send",
                        "https://mail.google.com/"),
                GmailScopes.getRequested());
        assertFalse(GmailScopes.getRequested().contains("https://www.googleapis.com/auth/gmail.compose"));
    }

    @Test
    public void grantedScopeValidationRequiresAllFourScopes() {
        assertTrue(GmailScopes.hasRequired(GmailScopes.getRequested()));
        assertFalse(GmailScopes.hasRequired(Arrays.asList(
                GmailScopes.READ, GmailScopes.MODIFY, GmailScopes.SEND)));
        assertFalse(GmailScopes.hasRequired(Arrays.asList(
                GmailScopes.READ, GmailScopes.MODIFY)));
        assertFalse(GmailScopes.hasRequired(Collections.<String>emptyList()));
        assertFalse(GmailScopes.hasRequired(null));
    }
}
