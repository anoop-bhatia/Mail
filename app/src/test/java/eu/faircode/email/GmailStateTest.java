package eu.faircode.email;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailStateTest {
    @Test
    public void browserOAuthCredentialsSurviveAccountStateSerialization() throws Exception {
        GmailState state = GmailState.authorizedWeb(
                "access-token", "refresh-token", "user@gmail.com", GmailWebOAuth.REST_BROWSER_CLIENT_ID);
        String serialized = state.jsonSerializeString();
        JSONObject json = new JSONObject(serialized);

        assertEquals(3, json.getInt("version"));
        assertEquals("refresh-token", json.getString("refreshToken"));
        assertEquals(GmailWebOAuth.REST_BROWSER_CLIENT_ID, json.getString("clientId"));
        assertTrue(GmailState.hasAuthorization(serialized));
        assertTrue(GmailState.isApiState(serialized));

        GmailState restored = GmailState.jsonDeserialize(serialized);
        assertEquals("access-token", restored.getAccessToken());
        assertEquals("refresh-token", new JSONObject(restored.jsonSerializeString()).getString("refreshToken"));
    }

    @Test
    public void versionTwoNativeIdentityServicesStateRemainsUsable() throws Exception {
        String legacy = "{\"version\":2,\"token\":\"old-access\",\"acquired\":1234,\"account\":\"user@gmail.com\"}";
        GmailState state = GmailState.jsonDeserialize(legacy);

        assertEquals("old-access", state.getAccessToken());
        assertTrue(GmailState.hasAuthorization(legacy));
        assertTrue(GmailState.isApiState(legacy));
        JSONObject upgraded = new JSONObject(state.jsonSerializeString());
        assertEquals(3, upgraded.getInt("version"));
        assertFalse(upgraded.has("refreshToken"));
        assertFalse(upgraded.has("clientId"));
    }
}
