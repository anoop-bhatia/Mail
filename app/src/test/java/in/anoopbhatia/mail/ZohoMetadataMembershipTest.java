package in.anoopbhatia.mail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class ZohoMetadataMembershipTest {
    private static final String MESSAGE_ID = "98765";
    private static final String SOURCE_FOLDER = "101";
    private static final String DESTINATION_FOLDER = "202";

    @Test
    public void sameFolderMetadataReturnsConfirmedIdFolderAndOptionalState() throws Exception {
        JSONObject data = metadata(MESSAGE_ID, SOURCE_FOLDER)
                .put("isRead", true)
                .put("flagId", 2);

        ZohoApi.MessageMetadata result = api(response(data))
                .getMessageMetadata(SOURCE_FOLDER, MESSAGE_ID);

        assertEquals(MESSAGE_ID, result.messageId);
        assertEquals(SOURCE_FOLDER, result.folderId);
        assertEquals(Boolean.TRUE, result.seen);
        assertEquals(Boolean.TRUE, result.flagged);
    }

    @Test
    public void differentValidFolderThrowsTypedExceptionWithConfirmedMetadataAndOptionalState() throws Exception {
        JSONObject data = metadata(MESSAGE_ID, DESTINATION_FOLDER)
                .put("status", "unread")
                .put("flagged", false);

        try {
            api(response(data)).getMessageMetadata(SOURCE_FOLDER, MESSAGE_ID);
            fail("A confirmed folder change must use the typed recovery exception");
        } catch (ZohoApi.MembershipChangedException expected) {
            assertNotNull(expected.metadata);
            assertEquals(MESSAGE_ID, expected.metadata.messageId);
            assertEquals(DESTINATION_FOLDER, expected.metadata.folderId);
            assertEquals(Boolean.FALSE, expected.metadata.seen);
            assertEquals(Boolean.FALSE, expected.metadata.flagged);
        }
    }

    @Test
    public void missingInvalidNullOverflowAndNegativeMessageIdsNeverBecomeTypedRecovery() throws Exception {
        String[] values = {"missing", "letters", "null", "overflow", "negative"};
        for (String value : values) {
            JSONObject data = metadata(value.equals("missing") ? null : MESSAGE_ID, DESTINATION_FOLDER);
            if ("missing".equals(value))
                data.remove("messageId");
            else if ("letters".equals(value))
                data.put("messageId", "not-numeric");
            else if ("null".equals(value))
                data.put("messageId", JSONObject.NULL);
            else if ("overflow".equals(value))
                data.put("messageId", "9223372036854775808");
            else
                data.put("messageId", "-1");
            assertNotMembership(response(data));
        }
    }

    @Test
    public void missingInvalidNullOverflowAndNegativeReturnedFolderIdsNeverBecomeTypedRecovery() throws Exception {
        String[] values = {"missing", "letters", "null", "overflow", "negative"};
        for (String value : values) {
            JSONObject data = metadata(MESSAGE_ID, DESTINATION_FOLDER);
            if ("missing".equals(value))
                data.remove("folderId");
            else if ("letters".equals(value))
                data.put("folderId", "not-numeric");
            else if ("null".equals(value))
                data.put("folderId", JSONObject.NULL);
            else if ("overflow".equals(value))
                data.put("folderId", "9223372036854775808");
            else
                data.put("folderId", "-1");
            assertNotMembership(response(data));
        }
    }

    @Test
    public void mismatchedMessageIdNeverBecomesTypedRecoveryEvenWithDifferentValidFolder() throws Exception {
        assertNotMembership(response(metadata("12345", DESTINATION_FOLDER)));
    }

    @Test
    public void missingDataAndMalformedDataNeverBecomeTypedRecovery() throws Exception {
        assertNotMembership("{\"status\":{\"code\":200}}");
        assertNotMembership("{\"data\":");
    }

    @Test
    public void invalidRequestedFolderNeverBecomesTypedRecovery() throws Exception {
        String[] requestedFolders = {null, "", "not-numeric", "-1", "9223372036854775808"};
        for (String requestedFolder : requestedFolders)
            assertNotMembership(response(metadata(MESSAGE_ID, DESTINATION_FOLDER)), requestedFolder);
    }

    private static JSONObject metadata(String messageId, String folderId) throws org.json.JSONException {
        JSONObject data = new JSONObject();
        if (messageId != null)
            data.put("messageId", messageId);
        if (folderId != null)
            data.put("folderId", folderId);
        return data;
    }

    private static String response(JSONObject data) throws org.json.JSONException {
        return new JSONObject().put("data", data).toString();
    }

    private static ZohoApi api(String response) {
        return new ZohoApi("access-fixture", "https://www.zohoapis.eu", "account-fixture",
                url -> new ZohoTestHttpsConnection(url, 200, response));
    }

    private static void assertNotMembership(String response) throws Exception {
        assertNotMembership(response, SOURCE_FOLDER);
    }

    private static void assertNotMembership(String response, String requestedFolder) throws Exception {
        try {
            api(response).getMessageMetadata(requestedFolder, MESSAGE_ID);
            fail("Invalid metadata must not be accepted");
        } catch (ZohoApi.MembershipChangedException unexpected) {
            fail("Invalid metadata must never use typed membership recovery");
        } catch (IOException expected) {
            assertFalse(expected instanceof ZohoApi.MembershipChangedException);
        } catch (org.json.JSONException expected) {
            // Malformed JSON is an ordinary transport/parse failure, not membership recovery.
        } catch (IllegalArgumentException expected) {
            assertEquals(true, requestedFolder == null || requestedFolder.isEmpty());
            assertEquals("Missing Zoho path identifier", expected.getMessage());
        }
    }
}
