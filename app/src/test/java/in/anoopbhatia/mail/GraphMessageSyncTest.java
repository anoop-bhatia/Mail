package in.anoopbhatia.mail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class GraphMessageSyncTest {
    @Test
    public void oldGraphId404FallsBackToStableInternetMessageId() throws Exception {
        EntityMessage message = message("old-id", "<stable@example.test>");
        String oldUrl = GraphMailApi.BASE + "/me/messages/old-id?$select=id,parentFolderId,isRead,flag";
        String findUrl = GraphMailApi.BASE + "/me/messages?$select=id,parentFolderId,isRead,flag" +
                "&$filter=internetMessageId%20eq%20%27%3Cstable%40example.test%3E%27&$top=2";
        List<String> requested = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requested.add(url.toString());
            if (oldUrl.equals(url.toString()))
                return new ZohoTestHttpsConnection(url, 404, "{\"error\":{\"code\":\"ErrorItemNotFound\"}}");
            if (findUrl.equals(url.toString()))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"value\":[{\"id\":\"new-id\",\"parentFolderId\":\"archive\",\"isRead\":true}]}");
            throw new AssertionError("Unexpected Graph URL " + url);
        });

        GraphMailApi.MessageState state = GraphMessageSync.resolve(api, message);

        assertEquals(java.util.Arrays.asList(oldUrl, findUrl), requested);
        assertEquals("new-id", state.id);
        assertEquals("archive", state.parentFolderId);
    }

    @Test
    public void non404OldGraphIdFailureDoesNotFallback() throws Exception {
        EntityMessage message = message("old-id", "<stable@example.test>");
        int[] requests = {0};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requests[0]++;
            return new ZohoTestHttpsConnection(url, 500, "{\"error\":{\"code\":\"InternalError\"}}");
        });

        try {
            GraphMessageSync.resolve(api, message);
            fail("Expected non-404 failure");
        } catch (GraphMailApi.ApiException ex) {
            assertEquals(500, ex.status);
        }
        assertEquals(1, requests[0]);
    }

    @Test
    public void deletedMessageWithNoStableMatchResolvesNull() throws Exception {
        EntityMessage message = message("old-id", "<stable@example.test>");
        int[] requests = {0};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requests[0]++;
            if (requests[0] == 1)
                return new ZohoTestHttpsConnection(url, 404, "{\"error\":{\"code\":\"ErrorItemNotFound\"}}");
            return new ZohoTestHttpsConnection(url, 200, "{\"value\":[]}");
        });

        assertNull(GraphMessageSync.resolve(api, message));
        assertEquals(2, requests[0]);
    }

    @Test
    public void duplicateStableInternetIdResolvesNull() throws Exception {
        EntityMessage message = message("old-id", "<stable@example.test>");
        int[] requests = {0};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requests[0]++;
            if (requests[0] == 1)
                return new ZohoTestHttpsConnection(url, 404, "{\"error\":{\"code\":\"ErrorItemNotFound\"}}");
            return new ZohoTestHttpsConnection(url, 200,
                    "{\"value\":[{\"id\":\"copy-1\"},{\"id\":\"copy-2\"}]}");
        });

        assertNull(GraphMessageSync.resolve(api, message));
        assertEquals(2, requests[0]);
    }

    @Test
    public void fallbackTransportFailurePropagatesWithoutReplacement() throws Exception {
        EntityMessage message = message("old-id", "<stable@example.test>");
        int[] requests = {0};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requests[0]++;
            if (requests[0] == 1)
                return new ZohoTestHttpsConnection(url, 404, "{\"error\":{\"code\":\"ErrorItemNotFound\"}}");
            throw new IOException("transport down");
        });

        try {
            GraphMessageSync.resolve(api, message);
            fail("Expected fallback transport failure");
        } catch (IOException ex) {
            assertEquals("transport down", ex.getMessage());
        }
        assertEquals(2, requests[0]);
    }

    @Test
    public void fallbackRateLimitFailurePropagatesWithoutReplacement() throws Exception {
        EntityMessage message = message("old-id", "<stable@example.test>");
        int[] requests = {0};
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requests[0]++;
            if (requests[0] == 1)
                return new ZohoTestHttpsConnection(url, 404, "{\"error\":{\"code\":\"ErrorItemNotFound\"}}");
            return new ZohoTestHttpsConnection(url, 429, "{\"error\":{\"code\":\"TooManyRequests\"}}");
        });

        try {
            GraphMessageSync.resolve(api, message);
            fail("Expected fallback rate-limit failure");
        } catch (GraphMailApi.ApiException ex) {
            assertEquals(429, ex.status);
            assertEquals(true, ex.isRateLimitFailure());
        }
        assertEquals(2, requests[0]);
    }

    private static EntityMessage message(String graphId, String internetMessageId) {
        EntityMessage message = new EntityMessage();
        message.graph_id = graphId;
        message.msgid = internetMessageId;
        return message;
    }
}
