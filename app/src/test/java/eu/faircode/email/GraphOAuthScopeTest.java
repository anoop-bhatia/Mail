package eu.faircode.email;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GraphOAuthScopeTest {
    @Test
    public void graphRequestPreservesConfiguredScopesAndRequestsReadWrite() {
        List<String> scopes = Arrays.asList(FragmentOAuth.getRequestedScopes(new String[]{
                "https://graph.microsoft.com/Mail.Send", "offline_access"
        }, true));

        assertTrue(scopes.contains("https://graph.microsoft.com/Mail.Send"));
        assertTrue(scopes.contains("offline_access"));
        assertTrue(scopes.contains("openid"));
        assertTrue(scopes.contains("profile"));
        assertTrue(scopes.contains("email"));
        assertTrue(scopes.contains("https://graph.microsoft.com/Mail.ReadWrite"));
        assertFalse(scopes.contains("https://graph.microsoft.com/Mail.Read"));
        assertEquals(6, scopes.size());
    }

    @Test
    public void graphClientIdMatchesBaseline() {
        assertEquals("835c3383-543c-4aa2-98ca-c465eb039987", GraphOAuth.CLIENT_ID);
    }
    @Test
    public void nonGraphOAuthRequestScopesRemainUnchanged() {
        String[] configured = new String[]{"scope-a", "scope-b"};
        assertEquals(Arrays.asList(configured),
                Arrays.asList(FragmentOAuth.getRequestedScopes(configured, false)));
    }
}
