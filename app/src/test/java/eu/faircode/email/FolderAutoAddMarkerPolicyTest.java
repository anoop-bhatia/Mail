package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FolderAutoAddMarkerPolicyTest {
    @Test
    public void hidesAutoAddMarkerForAllNativeRestProviders() {
        int[] authTypes = {
                ServiceAuthenticator.AUTH_TYPE_GMAIL,
                ServiceAuthenticator.AUTH_TYPE_ZOHO,
                ServiceAuthenticator.AUTH_TYPE_GRAPH_API
        };
        for (int authType : authTypes) {
            assertFalse("REST auth type " + authType,
                    FolderAutoAddMarkerPolicy.shouldShow(true, EntityFolder.SENT, null, authType));
            assertFalse("REST auth type " + authType,
                    FolderAutoAddMarkerPolicy.shouldShow(true, EntityFolder.SENT, true, authType));
        }
    }

    @Test
    public void retainsLegacyImapAndPopAutoAddMarkerBehavior() {
        // IMAP and POP accounts using the legacy password/OAuth/Graph auth paths are unaffected.
        int[] legacyAuthTypes = {
                ServiceAuthenticator.AUTH_TYPE_PASSWORD,
                ServiceAuthenticator.AUTH_TYPE_OAUTH,
                ServiceAuthenticator.AUTH_TYPE_GRAPH
        };
        for (int authType : legacyAuthTypes)
            assertTrue("legacy auth type " + authType,
                    FolderAutoAddMarkerPolicy.shouldShow(true, EntityFolder.SENT, null, authType));
    }

    @Test
    public void retainsExistingDebugSentAndAutoAddConditions() {
        assertFalse(FolderAutoAddMarkerPolicy.shouldShow(false, EntityFolder.SENT, null,
                ServiceAuthenticator.AUTH_TYPE_PASSWORD));
        assertFalse(FolderAutoAddMarkerPolicy.shouldShow(true, EntityFolder.INBOX, null,
                ServiceAuthenticator.AUTH_TYPE_PASSWORD));
        assertFalse(FolderAutoAddMarkerPolicy.shouldShow(true, EntityFolder.SENT, false,
                ServiceAuthenticator.AUTH_TYPE_PASSWORD));
        assertTrue(FolderAutoAddMarkerPolicy.shouldShow(true, EntityFolder.SENT, true,
                ServiceAuthenticator.AUTH_TYPE_PASSWORD));
        assertTrue(FolderAutoAddMarkerPolicy.shouldShow(true, EntityFolder.SENT, null,
                null));
    }
}
