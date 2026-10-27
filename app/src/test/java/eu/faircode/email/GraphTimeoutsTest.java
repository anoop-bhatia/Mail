package eu.faircode.email;

import org.junit.Test;

import java.net.URL;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;

public class GraphTimeoutsTest {
    @Test
    public void policyUsesTheOperationSpecificValuesAndHardDeadlines() {
        assertEquals(30_000, GraphTimeouts.CONNECT_MILLIS);
        assertEquals(30_000, GraphTimeouts.GET_RESPONSE_MILLIS);
        assertEquals(60_000, GraphTimeouts.MUTATION_RESPONSE_MILLIS);
        assertEquals(60_000, GraphTimeouts.MUTATION_WRITE_MILLIS);
        assertEquals(90_000, GraphTimeouts.DIRECT_SEND_RESPONSE_MILLIS);
        assertEquals(60_000, GraphTimeouts.DRAFT_CREATE_RESPONSE_MILLIS);
        assertEquals(90_000, GraphTimeouts.DRAFT_SEND_RESPONSE_MILLIS);
        assertEquals(60_000, GraphTimeouts.UPLOAD_SESSION_CREATE_RESPONSE_MILLIS);
        assertEquals(90_000, GraphTimeouts.UPLOAD_CHUNK_RESPONSE_MILLIS);
        assertEquals(300_000L, GraphTimeouts.MAX_OVERALL_UPLOAD_FLOW_MILLIS);
        assertEquals(300_000L, GraphTimeouts.MAX_OVERALL_SEND_MILLIS);
    }

    @Test
    public void graphGetAndMutationRoutesApplyTheirConnectAndResponseTimeouts() throws Exception {
        final ZohoTestHttpsConnection[] get = {null};
        GraphMailApi getApi = new GraphMailApi(null, "token", url -> {
            get[0] = new ZohoTestHttpsConnection(url, 200, "{\"value\":[]}");
            return get[0];
        });
        getApi.listMessages("inbox");
        assertEquals(GraphTimeouts.CONNECT_MILLIS, get[0].getConnectTimeout());
        assertEquals(GraphTimeouts.GET_RESPONSE_MILLIS, get[0].getReadTimeout());

        final ZohoTestHttpsConnection[] mutation = {null};
        GraphMailApi mutationApi = new GraphMailApi(null, "token", url -> {
            mutation[0] = new ZohoTestHttpsConnection(url, 204, "");
            return mutation[0];
        });
        mutationApi.setMessageRead("message-id", false);
        assertEquals(GraphTimeouts.CONNECT_MILLIS, mutation[0].getConnectTimeout());
        assertEquals(GraphTimeouts.MUTATION_RESPONSE_MILLIS, mutation[0].getReadTimeout());
    }

    @Test
    public void productionOkHttpMutationClientUsesThirtySecondConnectAndSixtySecondIoBounds()
            throws Exception {
        OkHttpClient client = GraphMailApi.createMutationClient();
        assertEquals(GraphTimeouts.CONNECT_MILLIS, client.connectTimeoutMillis());
        assertEquals(GraphTimeouts.MUTATION_RESPONSE_MILLIS, client.readTimeoutMillis());
        assertEquals(GraphTimeouts.MUTATION_WRITE_MILLIS, client.writeTimeoutMillis());

        // Keep request construction on the native Graph path; never route it through IMAP/SMTP.
        okhttp3.Request request = GraphMailApi.buildMutationRequest(
                new URL(GraphMailApi.BASE + "/me/messages/message-id/move"), "POST", "token",
                new org.json.JSONObject().put("destinationId", "archive"));
        assertEquals("POST", request.method());
        assertEquals("graph.microsoft.com", request.url().host());
    }
}
