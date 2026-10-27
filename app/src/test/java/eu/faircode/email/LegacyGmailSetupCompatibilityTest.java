package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LegacyGmailSetupCompatibilityTest {
    @Test
    public void accountManagerUsesGoogleAccountTypeAndGmailImapOauthToken() {
        assertEquals("com.google", GmailState.TYPE_GOOGLE);
        assertEquals("oauth2:https://mail.google.com/",
                ServiceAuthenticator.getAuthTokenType(GmailState.TYPE_GOOGLE));
        assertNull(ServiceAuthenticator.getAuthTokenType("com.example.other"));
    }
}
