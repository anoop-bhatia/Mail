package eu.faircode.email;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.json.JSONArray;

public class GmailApiTest {
    @Test
    public void queuedSeenUsesMessagesModifyAndRecoveredStateCompletesTheOperation() throws Exception {
        String modifyEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/modify";
        FakeConnection modify = new FakeConnection(new URL(modifyEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"INBOX\",\"STARRED\",\"Label_7\"]}", null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(modifyEndpoint, url.toString());
            return modify;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        GmailMailMutator.Plan seenPlan = GmailMailMutator.createSeenPlan(true);
        FakeOperationLifecycle interrupted = new FakeOperationLifecycle();

        List<String> changedLabels = GmailMailMutator.applyAndVerify(api, seenPlan,
                "gmail-id", interrupted);

        assertEquals("executing", interrupted.state);
        assertFalse(interrupted.completed);
        assertEquals(Collections.singletonList("UNREAD"), seenPlan.remove);
        assertTrue(seenPlan.add.isEmpty());
        assertEquals("POST", modify.method);
        assertEquals("{\"removeLabelIds\":[\"UNREAD\"]}",
                new String(modify.request.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(java.util.Arrays.asList("INBOX", "STARRED", "Label_7"), changedLabels);

        // Simulate process death after Gmail accepted the modify but before local queue completion.
        String labelsEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id?format=minimal";
        FakeConnection readback = new FakeConnection(new URL(labelsEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"INBOX\",\"STARRED\",\"Label_7\"]}", null);
        GmailApi recoveryApi = new GmailApi("test-access-token", url -> {
            assertEquals(labelsEndpoint, url.toString());
            return readback;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        FakeOperationLifecycle recovered = new FakeOperationLifecycle();
        recovered.state = "executing";
        List<String> remoteLabels = recoveryApi.getMessageLabels("gmail-id");

        assertEquals(remoteLabels, GmailMailMutator.applyAndCommit(recoveryApi, seenPlan,
                "gmail-id", remoteLabels, recovered));
        assertTrue(recovered.completed);
        assertEquals(remoteLabels, recovered.completedLabels);
        assertEquals("GET", readback.method);
    }

    @Test
    public void successfulModifyResponseLabelIdsAreTheLocalCommitSnapshot() throws Exception {
        String endpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/modify";
        FakeConnection modify = new FakeConnection(new URL(endpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"INBOX\",\"STARRED\",\"Label_7\"]}", null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(endpoint, url.toString());
            return modify;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        GmailMailMutator.Plan seenPlan = GmailMailMutator.createSeenPlan(true);
        FakeOperationLifecycle lifecycle = new FakeOperationLifecycle();

        List<String> committed = GmailMailMutator.applyAndCommit(api, seenPlan,
                "gmail-id", java.util.Arrays.asList("INBOX", "UNREAD", "STARRED", "Label_7"),
                lifecycle);

        assertEquals(java.util.Arrays.asList("INBOX", "STARRED", "Label_7"), committed);
        assertEquals(committed, lifecycle.completedLabels);
        assertTrue(lifecycle.completed);
        assertEquals("POST", modify.method);
    }

    @Test
    public void ambiguousModifyIsReadBackAndSatisfiedStateCompletesWithoutReplay() throws Exception {
        String modifyEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/modify";
        String getEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id?format=minimal";
        FakeConnection modify = new FakeConnection(new URL(modifyEndpoint), 500, null,
                "{\"error\":{\"message\":\"response lost after write\"}}");
        FakeConnection readback = new FakeConnection(new URL(getEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"INBOX\",\"STARRED\",\"Label_7\"]}", null);
        GmailApi api = scriptedApi(java.util.Arrays.asList(modifyEndpoint, getEndpoint),
                java.util.Arrays.asList(modify, readback));
        GmailMailMutator.Plan plan = GmailMailMutator.createSeenPlan(true);
        FakeOperationLifecycle operation = new FakeOperationLifecycle();
        FakeFailureLifecycle recovery = new FakeFailureLifecycle(
                java.util.Arrays.asList("INBOX", "UNREAD", "STARRED", "Label_7"));

        try {
            GmailMailMutator.applyAndVerify(api, plan, "gmail-id", operation);
            fail("Expected ambiguous REST response");
        } catch (IOException expected) {
            GmailMailMutator.RecoveryResult result = GmailMailMutator.reconcileFailedWrite(
                    api, "gmail-id", plan, recovery);
            assertEquals(GmailMailMutator.RecoveryResult.SATISFIED, result);
        }

        assertEquals(java.util.Arrays.asList("INBOX", "STARRED", "Label_7"), recovery.labels);
        assertFalse(recovery.pending);
        assertFalse(recovery.missing);
        assertFalse("The failed write is not blindly replayed", operation.completed);
        assertEquals("GET", readback.method);
    }

    @Test
    public void failedWriteReadbackReconcilesUnchangedServerLabelsAndClearsPendingState() throws Exception {
        String getEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id?format=minimal";
        FakeConnection readback = new FakeConnection(new URL(getEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"INBOX\",\"UNREAD\",\"Label_7\"]}", null);
        GmailApi api = scriptedApi(Collections.singletonList(getEndpoint),
                Collections.singletonList(readback));
        FakeFailureLifecycle recovery = new FakeFailureLifecycle(
                java.util.Arrays.asList("INBOX", "STARRED", "Label_7"));

        GmailMailMutator.RecoveryResult result = GmailMailMutator.reconcileFailedWrite(api,
                "gmail-id", GmailMailMutator.createSeenPlan(true), recovery);

        assertEquals(GmailMailMutator.RecoveryResult.NOT_SATISFIED, result);
        assertEquals(java.util.Arrays.asList("INBOX", "UNREAD", "Label_7"), recovery.labels);
        assertFalse(recovery.pending);
        assertFalse(recovery.missing);
    }

    @Test
    public void unavailableReadbackRetainsLastConfirmedLabelsAndKeepsMessageVisible() throws Exception {
        String getEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id?format=minimal";
        FakeConnection offline = new FakeConnection(new URL(getEndpoint), 503, null,
                "{\"error\":{\"message\":\"temporarily unavailable\"}}");
        GmailApi api = scriptedApi(Collections.singletonList(getEndpoint),
                Collections.singletonList(offline));
        List<String> lastConfirmed = java.util.Arrays.asList("INBOX", "UNREAD", "Label_7");
        FakeFailureLifecycle recovery = new FakeFailureLifecycle(lastConfirmed);

        GmailMailMutator.RecoveryResult result = GmailMailMutator.reconcileFailedWrite(api,
                "gmail-id", GmailMailMutator.createSeenPlan(true), recovery);

        assertEquals(GmailMailMutator.RecoveryResult.UNAVAILABLE, result);
        assertEquals(lastConfirmed, recovery.labels);
        assertTrue(recovery.pendingCleared);
        assertFalse(recovery.missing);
        assertTrue(offline.disconnected);
    }

    @Test
    public void confirmedMessage404CompletesRecoveryAsAbsent() throws Exception {
        String getEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id?format=minimal";
        FakeConnection missing = new FakeConnection(new URL(getEndpoint), 404, null,
                "{\"error\":{\"message\":\"message not found\"}}");
        GmailApi api = scriptedApi(Collections.singletonList(getEndpoint),
                Collections.singletonList(missing));
        FakeFailureLifecycle recovery = new FakeFailureLifecycle(Collections.singletonList("INBOX"));

        GmailMailMutator.RecoveryResult result = GmailMailMutator.reconcileFailedWrite(api,
                "gmail-id", GmailMailMutator.createSeenPlan(true), recovery);

        assertEquals(GmailMailMutator.RecoveryResult.MISSING, result);
        assertTrue(recovery.missing);
        assertFalse(recovery.pending);
    }

    @Test
    public void queuedInboxToNotificationsMovePostsOnlyTheLabelDeltaAndPreservesOtherMemberships() throws Exception {
        String endpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/modify";
        FakeConnection modify = new FakeConnection(new URL(endpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"STARRED\",\"Label_Keep\",\"CATEGORY_PERSONAL\",\"Label_Notifications\"]}", null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(endpoint, url.toString());
            return modify;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        // Same serialized target/source positions used by EntityOperation.queue from the picker.
        JSONArray queuedArgs = new JSONArray("[31,null,null,true,null,\"INBOX\"]");
        assertEquals(31L, queuedArgs.optLong(0));
        String sourceLabel = GmailMailMutator.resolveMoveSourceLabelFromOperationArgs(
                queuedArgs, "INBOX");
        GmailMailMutator.MovePlan move = GmailMailMutator.createMovePlan(
                EntityFolder.USER, "Label_Notifications", EntityFolder.INBOX,
                sourceLabel, false,
                java.util.Arrays.asList("INBOX", "STARRED", "Label_Keep", "CATEGORY_PERSONAL"));
        GmailMailMutator.Plan requestPlan = new GmailMailMutator.Plan();
        requestPlan.add = move.add;
        requestPlan.remove = move.remove;

        FakeOperationLifecycle lifecycle = new FakeOperationLifecycle();
        List<String> changedLabels = GmailMailMutator.applyAndVerify(api, requestPlan,
                "gmail-id", lifecycle);

        assertEquals(Collections.singletonList("Label_Notifications"), move.add);
        assertEquals(Collections.singletonList("INBOX"), move.remove);
        assertEquals("POST", modify.method);
        assertEquals("{\"addLabelIds\":[\"Label_Notifications\"],\"removeLabelIds\":[\"INBOX\"]}",
                new String(modify.request.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(java.util.Arrays.asList("STARRED", "Label_Keep", "CATEGORY_PERSONAL",
                "Label_Notifications"), changedLabels);
        assertEquals("executing", lifecycle.state);
        assertFalse(lifecycle.completed);
        assertTrue(GmailMailMutator.completeIfSatisfied(requestPlan, changedLabels, lifecycle));
        assertTrue(lifecycle.completed);
        assertEquals(changedLabels, lifecycle.completedLabels);
    }

    @Test
    public void queuedUserLabelToInboxMovePostsExactLabelIdsAndCommitsResponseSnapshot() throws Exception {
        String endpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/modify";
        FakeConnection modify = new FakeConnection(new URL(endpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"INBOX\",\"STARRED\",\"CATEGORY_SOCIAL\",\"Label_Keep\"]}", null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(endpoint, url.toString());
            return modify;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        // Durable EntityOperation MOVE arguments retain the selected Inbox and source user label.
        JSONArray queuedArgs = EntityOperation.createOperationArgs(
                41L, null, null, true, null, "Label_Project");
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.MOVE));
        assertEquals(41L, queuedArgs.optLong(0));
        String sourceLabel = GmailMailMutator.resolveMoveSourceLabelFromOperationArgs(
                queuedArgs, "Label_Project");
        GmailMailMutator.MovePlan move = GmailMailMutator.createMovePlan(
                EntityFolder.INBOX, "INBOX", EntityFolder.USER,
                sourceLabel, true, java.util.Arrays.asList("Label_Project", "STARRED",
                        "CATEGORY_SOCIAL", "Label_Keep"));
        GmailMailMutator.Plan requestPlan = new GmailMailMutator.Plan();
        requestPlan.add = move.add;
        requestPlan.remove = move.remove;
        FakeOperationLifecycle lifecycle = new FakeOperationLifecycle();

        List<String> committed = GmailMailMutator.applyAndCommit(api, requestPlan,
                "gmail-id", java.util.Arrays.asList("Label_Project", "STARRED",
                        "CATEGORY_SOCIAL", "Label_Keep"), lifecycle);

        assertEquals("Label_Project", sourceLabel);
        assertEquals(Collections.singletonList("INBOX"), move.add);
        assertEquals(Collections.singletonList("Label_Project"), move.remove);
        assertEquals("POST", modify.method);
        assertEquals("{\"addLabelIds\":[\"INBOX\"],\"removeLabelIds\":[\"Label_Project\"]}",
                new String(modify.request.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(java.util.Arrays.asList("INBOX", "STARRED", "CATEGORY_SOCIAL", "Label_Keep"),
                committed);
        assertEquals(committed, lifecycle.completedLabels);
        assertTrue(lifecycle.completed);
        assertEquals(20_000, modify.getReadTimeout());
    }

    @Test
    public void gmailSendUsesNinetySecondTimeoutAndQuarantinesPostUploadSocketTimeout() throws Exception {
        FakeConnection connection = new FakeConnection(new URL(GmailApi.SEND_ENDPOINT), 200,
                "{}", null) {
            @Override
            public int getResponseCode() throws IOException {
                throw new SocketTimeoutException("response timed out");
            }
        };
        GmailApi api = new GmailApi("test-access-token", url -> connection,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        try {
            api.send(createMessage());
            fail("Expected unknown Gmail delivery outcome");
        } catch (GmailApi.SendOutcomeUnknownException ex) {
            assertTrue(GmailSendAttempt.isOutcomeUnknown(ex));
            assertTrue(ex.getMessage().contains("no HTTP response"));
        }

        assertEquals(90_000, connection.getConnectTimeout());
        assertEquals(90_000, connection.getReadTimeout());
        assertTrue(connection.request.size() > 0);
        assertTrue(connection.disconnected);

        EntityOperation gmail = sendOperation(71L);
        EntityOperation zoho = sendOperation(72L);
        java.util.List<EntityOperation> queue = new java.util.ArrayList<>(
                java.util.Arrays.asList(gmail, zoho));
        assertTrue(GmailSendAttempt.continueQueueAfterFailure(queue, gmail,
                new GmailApi.SendOutcomeUnknownException(new IOException("timed out"))));
        gmail.error = EntityOperation.GMAIL_SEND_OUTCOME_UNKNOWN_ERROR;
        assertTrue(EntityOperation.isSendOutcomeUnknown(gmail));
        assertFalse(EntityOperation.isSendOutcomeUnknown(zoho));
        assertEquals(1, queue.size());
        assertTrue(queue.get(0) == zoho);
    }

    @Test
    public void gmailSendQuarantinesServerErrorAfterUploadBecauseAcceptanceIsUnclear() throws Exception {
        FakeConnection connection = new FakeConnection(new URL(GmailApi.SEND_ENDPOINT), 503,
                null, "{\"error\":{\"code\":503,\"message\":\"temporary failure\"}}");
        GmailApi api = new GmailApi("test-access-token", url -> connection,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        try {
            api.send(createMessage());
            fail("Expected unknown Gmail delivery outcome for HTTP 503");
        } catch (GmailApi.SendOutcomeUnknownException ex) {
            assertEquals(503, ex.status);
            assertTrue(GmailSendAttempt.isOutcomeUnknown(ex));
        }
        assertTrue(connection.request.size() > 0);
    }

    @Test
    public void gmailSendTreatsAnySuccessfulResponseAsAcceptedEvenIfBodyReadFails() throws Exception {
        FakeConnection connection = new FakeConnection(new URL(GmailApi.SEND_ENDPOINT), 200,
                "{}", null) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("response body read failed after HTTP 200");
            }
        };
        GmailApi api = new GmailApi("test-access-token", url -> connection,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        assertEquals("", api.send(createMessage()));
        assertTrue(connection.disconnected);
    }

    @Test
    public void ordinaryGmailListKeepsTwentySecondTimeout() throws Exception {
        FakeConnection connection = new FakeConnection(new URL(GmailApi.LABELS_ENDPOINT), 200,
                "{\"labels\":[]}", null);
        GmailApi api = new GmailApi("test-access-token", url -> connection,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        api.listLabels();

        assertEquals(GmailApi.TIMEOUT_SECONDS * 1000, connection.getConnectTimeout());
        assertEquals(GmailApi.TIMEOUT_SECONDS * 1000, connection.getReadTimeout());
        assertTrue(connection.disconnected);
    }

    @Test
    public void sendUsesGmailNativeRawMimeRequestAndPreservesResponse() throws Exception {
        String gmailResponse = "{\"id\":\"gmail-message-id\",\"threadId\":\"gmail-thread-id\"}";
        FakeConnection connection = new FakeConnection(
                new URL(GmailApi.SEND_ENDPOINT), 200, gmailResponse, null);
        final int[] chargedUnits = {0};
        GmailApi api = new GmailApi("test-access-token",
                url -> {
                    assertEquals(GmailApi.SEND_ENDPOINT, url.toString());
                    return connection;
                },
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        api.setQuotaUsageListener(units -> chargedUnits[0] += units);
        MimeMessage message = createMessage();
        ByteArrayOutputStream expectedMime = new ByteArrayOutputStream();
        message.writeTo(expectedMime);

        String response = api.send(message);

        assertEquals(gmailResponse, response);
        assertEquals(100, chargedUnits[0]);
        assertEquals("POST", connection.method);
        assertEquals("Bearer test-access-token", connection.headers.get("Authorization"));
        assertEquals("application/json", connection.headers.get("Accept"));
        assertEquals("application/json; charset=UTF-8", connection.headers.get("Content-Type"));
        assertTrue(connection.disconnected);

        String request = new String(connection.request.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(request.startsWith("{\"raw\":\""));
        assertTrue(request.endsWith("\"}"));
        String encoded = request.substring("{\"raw\":\"".length(), request.length() - 2);
        assertFalse("Gmail raw payload should use unpadded base64url", encoded.contains("="));
        assertArrayEquals(expectedMime.toByteArray(), Base64.getUrlDecoder().decode(encoded));
    }

    @Test
    public void replySendIncludesProviderThreadIdAndPreservesRfcReplyHeaders() throws Exception {
        FakeConnection connection = new FakeConnection(new URL(GmailApi.SEND_ENDPOINT), 200,
                "{\"id\":\"sent-id\",\"threadId\":\"provider-thread\"}", null);
        GmailApi api = new GmailApi("test-access-token", url -> connection,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        MimeMessage message = createMessage();
        message.setHeader("In-Reply-To", "<parent@example.invalid>");
        message.setHeader("References", "<root@example.invalid> <parent@example.invalid>");
        message.saveChanges();

        api.send(message, "provider-thread");

        JSONObject payload = new JSONObject(connection.request.toString(StandardCharsets.UTF_8.name()));
        assertEquals("provider-thread", payload.getString("threadId"));
        byte[] raw = Base64.getUrlDecoder().decode(payload.getString("raw"));
        String mime = new String(raw, StandardCharsets.UTF_8);
        assertTrue(mime.contains("In-Reply-To: <parent@example.invalid>"));
        assertTrue(mime.contains("References: <root@example.invalid> <parent@example.invalid>"));
        assertTrue(mime.contains("Subject: Native Gmail API test"));
    }

    @Test
    public void gmailAttachmentDecoderEnforcesEncodedAndDecodedBoundaries() throws Exception {
        byte[] boundary = "1234".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(boundary);
        assertArrayEquals(boundary, GmailApi.decodeAttachmentData(encoded, boundary.length,
                value -> Base64.getUrlDecoder().decode(value)));

        String tooLarge = Base64.getUrlEncoder().withoutPadding().encodeToString("12345".getBytes(StandardCharsets.UTF_8));
        try {
            GmailApi.decodeAttachmentData(tooLarge, boundary.length,
                    value -> Base64.getUrlDecoder().decode(value));
            fail("Expected a decoded-size rejection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("size limit"));
        }

        assertTrue(GmailApi.MAX_ATTACHMENT_RESPONSE_BYTES >
                ((GmailApi.MAX_ATTACHMENT_BYTES + 2L) / 3L) * 4L);
    }

    @Test
    public void gmailAttachmentResponseReaderAcceptsTheLimitAndRejectsTheNextByte() throws Exception {
        assertEquals("1234", GmailApi.readResponse(
                new ByteArrayInputStream("1234".getBytes(StandardCharsets.UTF_8)), 4));
        try {
            GmailApi.readResponse(new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)), 4);
            fail("The first byte over the response limit must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("size limit"));
        }
    }

    @Test
    public void sendReportsNativeGmailApiErrorBody() throws Exception {
        String errorBody = "{\"error\":{\"code\":403,\"message\":\"insufficient permission\"}}";
        FakeConnection connection = new FakeConnection(
                new URL(GmailApi.SEND_ENDPOINT), 403, null, errorBody);
        GmailApi api = new GmailApi("test-access-token", url -> connection,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        try {
            api.send(createMessage());
            fail("Expected Gmail API HTTP error");
        } catch (IOException ex) {
            assertTrue(ex.getMessage().contains("HTTP 403"));
            assertTrue(ex.getMessage().contains(errorBody));
        }
        assertTrue(connection.disconnected);
    }

    @Test
    public void missingTokenIsRejectedBeforeNetworkAccess() throws Exception {
        try {
            new GmailApi(" ", url -> {
                throw new AssertionError("Network must not be opened");
            },
                    output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
            fail("Expected missing token error");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("Missing Gmail access token"));
        }
    }

    @Test
    public void profileUsesGmailApiAndReturnsMailboxAddress() throws Exception {
        String response = "{\"emailAddress\":\"user@gmail.com\",\"historyId\":\"42\"," +
                "\"messagesTotal\":7,\"threadsTotal\":5}";
        FakeConnection connection = new FakeConnection(new URL(GmailApi.PROFILE_ENDPOINT), 200, response, null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(GmailApi.PROFILE_ENDPOINT, url.toString());
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        GmailApi.Profile profile = api.getProfile();

        assertEquals("user@gmail.com", profile.emailAddress);
        assertEquals("42", profile.historyId);
        assertEquals(7, profile.messagesTotal);
        assertEquals(5, profile.threadsTotal);
        assertEquals("GET", connection.method);
        assertEquals("Bearer test-access-token", connection.headers.get("Authorization"));
        assertTrue(connection.disconnected);
    }

    @Test
    public void profileRejectsResponseWithoutMailboxAddress() throws Exception {
        FakeConnection connection = new FakeConnection(
                new URL(GmailApi.PROFILE_ENDPOINT), 200, "{\"historyId\":\"42\"}", null);
        GmailApi api = new GmailApi("test-access-token", url -> connection,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        try {
            api.getProfile();
            fail("Expected missing profile email error");
        } catch (IOException ex) {
            assertTrue(ex.getMessage().contains("did not contain an email address"));
        }
        assertTrue(connection.disconnected);
    }

    @Test
    public void unauthorizedGetReacquiresTokenAndRetriesExactlyOnce() throws Exception {
        FakeConnection unauthorized = new FakeConnection(new URL(GmailApi.PROFILE_ENDPOINT), 401, null,
                "{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\"}}");
        FakeConnection success = new FakeConnection(new URL(GmailApi.PROFILE_ENDPOINT), 200,
                "{\"emailAddress\":\"user@gmail.com\",\"historyId\":\"42\"}", null);
        final int[] requests = {0};
        final int[] refreshes = {0};
        final int[] quotaUnits = {0};
        GmailApi api = new GmailApi("stale-token", url -> {
            assertEquals(GmailApi.PROFILE_ENDPOINT, url.toString());
            return (requests[0]++ == 0 ? unauthorized : success);
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        api.setTokenRefresher(rejected -> {
            refreshes[0]++;
            assertEquals("stale-token", rejected);
            return "fresh-token";
        });
        api.setQuotaUsageListener(units -> quotaUnits[0] += units);

        GmailApi.Profile profile = api.getProfile();

        assertEquals("user@gmail.com", profile.emailAddress);
        assertEquals(2, requests[0]);
        assertEquals(1, refreshes[0]);
        assertEquals("Charge both the rejected request and its one replay", 2, quotaUnits[0]);
        assertEquals("Bearer stale-token", unauthorized.getRequestProperty("Authorization"));
        assertEquals("Bearer fresh-token", success.getRequestProperty("Authorization"));
        assertTrue(unauthorized.disconnected);
        assertTrue(success.disconnected);
    }

    @Test
    public void secondUnauthorizedResponseDoesNotRefreshOrRetryAgain() throws Exception {
        final int[] requests = {0};
        final int[] refreshes = {0};
        GmailApi api = new GmailApi("stale-token", url -> {
            requests[0]++;
            return new FakeConnection(url, 401, null,
                    "{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\"}}");
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        api.setTokenRefresher(rejected -> {
            refreshes[0]++;
            return "fresh-token";
        });

        try {
            api.getProfile();
            fail("Expected the retried 401 to be reported");
        } catch (GmailApi.ApiException ex) {
            assertEquals(401, ex.status);
        }
        assertEquals(2, requests[0]);
        assertEquals("The refresh callback must run exactly once", 1, refreshes[0]);
    }

    @Test
    public void unchangedRejectedTokenIsNotReplayedAndRequestsReauthorization() throws Exception {
        final int[] requests = {0};
        GmailApi api = new GmailApi("stale-token", url -> {
            requests[0]++;
            return new FakeConnection(url, 401, null,
                    "{\"error\":{\"message\":\"Invalid Credentials\"}}");
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        api.setTokenRefresher(rejected -> rejected);

        try {
            api.getProfile();
            fail("Expected explicit Gmail reauthorization guidance");
        } catch (GmailApi.ApiException ex) {
            assertEquals(401, ex.status);
            assertTrue(ex.getMessage().contains("Gmail requires Google authorization"));
        }
        assertEquals("A known-bad bearer token must never be replayed", 1, requests[0]);
    }

    @Test
    public void quota403PreservesRetryAfterAndIsDistinctFromPermission403() throws Exception {
        FakeConnection quota = new FakeConnection(new URL(GmailApi.LABELS_ENDPOINT), 403, null,
                "{\"error\":{\"code\":403,\"message\":\"PERMISSION_DENIED quota.metric Total Query Cost\"}}");
        quota.responseHeaders.put("Retry-After", "75");
        GmailApi api = new GmailApi("test-access-token", url -> quota,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        try {
            api.listLabels();
            fail("Expected quota 403");
        } catch (GmailApi.ApiException ex) {
            assertTrue(ex.isRateLimitFailure());
            assertEquals(75_000L, ex.retryAfterMs);
        }

        FakeConnection permission = new FakeConnection(new URL(GmailApi.LABELS_ENDPOINT), 403, null,
                "{\"error\":{\"code\":403,\"message\":\"insufficient permission\"}}");
        GmailApi other = new GmailApi("test-access-token", url -> permission,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        try {
            other.listLabels();
            fail("Expected permission 403");
        } catch (GmailApi.ApiException ex) {
            assertFalse(ex.isRateLimitFailure());
        }
    }

    @Test
    public void http429IsClassifiedAsRateLimit() throws Exception {
        FakeConnection connection = new FakeConnection(new URL(GmailApi.LABELS_ENDPOINT), 429, null,
                "{\"error\":{\"code\":429,\"message\":\"Too Many Requests\"}}");
        GmailApi api = new GmailApi("test-access-token", url -> connection,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        try {
            api.listLabels();
            fail("Expected Gmail 429 rate limit");
        } catch (GmailApi.ApiException ex) {
            assertEquals(429, ex.status);
            assertTrue(ex.isRateLimitFailure());
        }
        assertTrue(connection.disconnected);
    }

    @Test
    public void retryAfterParsesSecondsAndHttpDates() {
        long now = 1_800_000_000_000L;
        assertEquals(75_000L, GmailApi.parseRetryAfter("75", now));
        assertEquals(60_000L,
                GmailApi.parseRetryAfter("Fri, 15 Jan 2027 08:01:00 GMT", now));
        assertEquals(-1L, GmailApi.parseRetryAfter("not-a-date", now));
    }

    @Test
    public void listMessagesPreservesGmailIdsAndPaginationToken() throws Exception {
        String response = "{\"messages\":[{" +
                "\"id\":\"opaque-gmail-id\",\"threadId\":\"gmail-thread\"}]," +
                "\"nextPageToken\":\"next/page token\",\"resultSizeEstimate\":17}";
        String expectedUrl = GmailApi.MESSAGES_ENDPOINT +
                "?maxResults=50&q=in%3Ainbox+newer_than%3A1d&pageToken=previous%2Fpage+token";
        FakeConnection connection = new FakeConnection(new URL(expectedUrl), 200, response, null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(expectedUrl, url.toString());
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        GmailApi.MessagePage page = api.listMessages(
                "in:inbox newer_than:1d", "previous/page token", 50);

        assertEquals("GET", connection.method);
        assertEquals("Bearer test-access-token", connection.headers.get("Authorization"));
        assertEquals("application/json", connection.headers.get("Accept"));
        assertEquals(1, page.messages.size());
        assertEquals("opaque-gmail-id", page.messages.get(0).id);
        assertEquals("gmail-thread", page.messages.get(0).threadId);
        assertEquals("next/page token", page.nextPageToken);
        assertEquals(17, page.resultSizeEstimate);
        assertTrue(connection.disconnected);
    }

    @Test
    public void manualFolderPageIsTenMessagesAndLabelScoped() throws Exception {
        String expectedUrl = GmailApi.MESSAGES_ENDPOINT +
                "?maxResults=10&labelIds=Label_42&includeSpamTrash=true&pageToken=next%2Fpage+token";
        FakeConnection connection = new FakeConnection(new URL(expectedUrl), 200,
                "{\"messages\":[{\"id\":\"folder-message\"}],\"nextPageToken\":\"older\"}", null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(expectedUrl, url.toString());
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        GmailApi.MessagePage page = api.listMessagesForFolder(
                "Label_42", null, "next/page token", 10, true);

        assertEquals(1, page.messages.size());
        assertEquals("folder-message", page.messages.get(0).id);
        assertEquals("older", page.nextPageToken);
        assertTrue(connection.disconnected);
    }

    @Test
    public void metadataRepairCombinesInternalDateAndLabelsInOneProjectedGetAndPreservesLocalRow() throws Exception {
        String gmailId = "linked-message-17";
        String expectedUrl = GmailApi.MESSAGES_ENDPOINT + "/" + gmailId +
                "?format=full&fields=id%2CthreadId%2CinternalDate%2ClabelIds";
        String response = "{\"id\":\"linked-message-17\",\"threadId\":\"thread-17\"," +
                "\"internalDate\":\"1727700000123\",\"labelIds\":[\"INBOX\",\"STARRED\"]}";
        FakeConnection connection = new FakeConnection(new URL(expectedUrl), 200, response, null);
        final int[] requests = {0};
        GmailApi api = new GmailApi("test-access-token", url -> {
            requests[0]++;
            assertEquals(expectedUrl, url.toString());
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        EntityMessage cached = new EntityMessage();
        cached.id = 17L;
        cached.gmail_id = gmailId;
        cached.gmail_thread_id = "thread-17";
        cached.gmail_received_checked = false;
        cached.received = 1_000L;
        cached.sent = 2_000L;
        cached.subject = "Keep this subject";
        cached.headers = "Keep these headers";
        cached.preview = "Keep this preview";
        cached.content = true;
        cached.attachments = 3;
        cached.seen = true;
        cached.flagged = true;
        cached.deleted = false;
        cached.ui_seen = true;
        cached.ui_flagged = true;
        cached.ui_deleted = false;
        cached.labels = new String[]{"LOCAL_LABEL"};

        final int[] updates = {0};
        boolean repaired = GmailReceiver.repairReceivedDateIfNeeded(api, cached, (id, received) -> {
            assertEquals(17L, id);
            assertEquals(1_727_700_000_123L, received);
            updates[0]++;
            return 1;
        });

        assertTrue(repaired);
        assertEquals(1_727_700_000_123L, cached.received.longValue());
        assertEquals("The RFC-derived sent timestamp is not changed", 2_000L, cached.sent.longValue());
        assertEquals(gmailId, cached.gmail_id);
        assertEquals("thread-17", cached.gmail_thread_id);
        assertEquals("Keep this subject", cached.subject);
        assertEquals("Keep these headers", cached.headers);
        assertEquals("Keep this preview", cached.preview);
        assertEquals(Boolean.TRUE, cached.content);
        assertEquals(Integer.valueOf(3), cached.attachments);
        assertEquals(Boolean.TRUE, cached.seen);
        assertEquals(Boolean.TRUE, cached.flagged);
        assertEquals(Boolean.FALSE, cached.deleted);
        assertEquals(Boolean.TRUE, cached.ui_seen);
        assertEquals(Boolean.TRUE, cached.ui_flagged);
        assertEquals(Boolean.FALSE, cached.ui_deleted);
        assertEquals(java.util.Arrays.asList("LOCAL_LABEL"), java.util.Arrays.asList(cached.labels));
        assertEquals(Boolean.TRUE, cached.gmail_received_checked);

        assertFalse("A verified row is not fetched again on another folder/page",
                GmailReceiver.repairReceivedDateIfNeeded(api, cached, (id, received) -> {
                    updates[0]++;
                    return 1;
                }));
        assertEquals("One list reference can cause at most one metadata GET", 1, requests[0]);
        assertEquals("The date and one-time marker are persisted in one update", 1, updates[0]);
        assertTrue(connection.disconnected);
    }

    @Test
    public void tenListedLinkedRowsUseOneMetadataGetEachWithinTheExplicitPageBudget() throws Exception {
        String listUrl = GmailApi.MESSAGES_ENDPOINT +
                "?maxResults=10&labelIds=Label_42&includeSpamTrash=true";
        Map<String, FakeConnection> connections = new HashMap<>();
        connections.put(listUrl, new FakeConnection(new URL(listUrl), 200,
                messagePageResponse(null, "linked-01", "linked-02", "linked-03", "linked-04",
                        "linked-05", "linked-06", "linked-07", "linked-08", "linked-09", "linked-10"), null));
        for (int i = 1; i <= 10; i++) {
            String id = String.format(java.util.Locale.ROOT, "linked-%02d", i);
            String metadataUrl = GmailApi.MESSAGES_ENDPOINT + "/" + id +
                    "?format=full&fields=id%2CthreadId%2CinternalDate%2ClabelIds";
            String date = Long.toString(1_727_700_000_000L + i);
            connections.put(metadataUrl, new FakeConnection(new URL(metadataUrl), 200,
                    "{\"id\":\"" + id + "\",\"threadId\":\"thread\",\"internalDate\":\"" +
                            date + "\",\"labelIds\":[\"Label_42\"]}", null));
        }
        final int[] httpRequests = {0};
        GmailApi api = new GmailApi("test-access-token", url -> {
            httpRequests[0]++;
            FakeConnection connection = connections.remove(url.toString());
            if (connection == null)
                throw new AssertionError("Unexpected request outside this ten-result label page: " + url);
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        GmailApi.MessagePage page = api.listMessagesForFolder("Label_42", null, null, 10, true);
        assertEquals(10, page.messages.size());
        int repaired = 0;
        for (int i = 0; i < page.messages.size(); i++) {
            GmailApi.MessageReference reference = page.messages.get(i);
            final int index = i;
            EntityMessage cached = new EntityMessage();
            cached.id = (long) index + 1;
            cached.gmail_id = reference.id;
            cached.gmail_received_checked = false;
            assertTrue(GmailReceiver.repairReceivedDateIfNeeded(api, cached, (id, received) -> {
                assertEquals((long) index + 1, id);
                assertEquals(1_727_700_000_000L + index + 1, received);
                return 1;
            }));
            repaired++;
        }

        assertEquals(10, repaired);
        assertEquals("One label-scoped list plus one GET per listed ID", 11, httpRequests[0]);
        assertEquals("5 list units plus ten 20-unit message GETs", 205L, 5L + repaired * 20L);
        assertTrue(connections.isEmpty());
    }

    @Test
    public void newestInboxTenAndNextPagesKeepFolderFiltersAndCursorsSeparate() throws Exception {
        String inboxFirstUrl = GmailApi.MESSAGES_ENDPOINT + "?maxResults=10&labelIds=INBOX";
        String inboxNextUrl = GmailApi.MESSAGES_ENDPOINT +
                "?maxResults=10&labelIds=INBOX&pageToken=inbox%2Fpage+2";
        String labelFirstUrl = GmailApi.MESSAGES_ENDPOINT +
                "?maxResults=10&labelIds=Label_42&includeSpamTrash=true";
        String labelNextUrl = GmailApi.MESSAGES_ENDPOINT +
                "?maxResults=10&labelIds=Label_42&includeSpamTrash=true&pageToken=label%2Fpage+2";
        Map<String, FakeConnection> connections = new HashMap<>();
        connections.put(inboxFirstUrl, new FakeConnection(new URL(inboxFirstUrl), 200,
                messagePageResponse("inbox/page 2", "newest-01", "newest-02", "newest-03",
                        "newest-04", "newest-05", "newest-06", "newest-07", "newest-08",
                        "newest-09", "newest-10"), null));
        connections.put(inboxNextUrl, new FakeConnection(new URL(inboxNextUrl), 200,
                messagePageResponse("inbox/page 3", "older-01", "older-02", "older-03",
                        "older-04", "older-05", "older-06", "older-07", "older-08",
                        "older-09", "older-10"), null));
        connections.put(labelFirstUrl, new FakeConnection(new URL(labelFirstUrl), 200,
                messagePageResponse("label/page 2", "label-newest-01", "label-newest-02",
                        "label-newest-03", "label-newest-04", "label-newest-05", "label-newest-06",
                        "label-newest-07", "label-newest-08", "label-newest-09", "label-newest-10"), null));
        connections.put(labelNextUrl, new FakeConnection(new URL(labelNextUrl), 200,
                messagePageResponse("label/page 3", "label-older-01", "label-older-02",
                        "label-older-03", "label-older-04", "label-older-05", "label-older-06",
                        "label-older-07", "label-older-08", "label-older-09", "label-older-10"), null));
        GmailApi api = new GmailApi("test-access-token", url -> {
            FakeConnection connection = connections.remove(url.toString());
            if (connection == null)
                throw new AssertionError("Unexpected or cross-folder Gmail page request: " + url);
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        GmailApi.MessagePage inboxFirst = api.listMessagesForFolder("INBOX", null, null, 10, false);
        GmailApi.MessagePage inboxNext = api.listMessagesForFolder(
                "INBOX", null, inboxFirst.nextPageToken, 10, false);
        GmailApi.MessagePage labelFirst = api.listMessagesForFolder("Label_42", null, null, 10, true);
        GmailApi.MessagePage labelNext = api.listMessagesForFolder(
                "Label_42", null, labelFirst.nextPageToken, 10, true);

        assertEquals(10, inboxFirst.messages.size());
        assertEquals(java.util.Arrays.asList("newest-01", "newest-02", "newest-03", "newest-04",
                "newest-05", "newest-06", "newest-07", "newest-08", "newest-09", "newest-10"),
                messageIds(inboxFirst));
        assertEquals(java.util.Arrays.asList("older-01", "older-02", "older-03", "older-04",
                "older-05", "older-06", "older-07", "older-08", "older-09", "older-10"),
                messageIds(inboxNext));
        assertEquals("inbox/page 2", inboxFirst.nextPageToken);
        assertEquals("inbox/page 3", inboxNext.nextPageToken);
        assertEquals("label/page 2", labelFirst.nextPageToken);
        assertEquals("label/page 3", labelNext.nextPageToken);
        assertEquals(java.util.Arrays.asList("label-older-01", "label-older-02", "label-older-03",
                "label-older-04", "label-older-05", "label-older-06", "label-older-07",
                "label-older-08", "label-older-09", "label-older-10"), messageIds(labelNext));
        assertTrue(connections.isEmpty());
    }

    @Test
    public void listLabelsParsesSystemAndUserLabelsWithTheExistingReadGrant() throws Exception {
        String response = "{\"labels\":[" +
                "{\"id\":\"INBOX\",\"name\":\"Inbox\",\"type\":\"SYSTEM\"}," +
                "{\"id\":\"SENT\",\"name\":\"Sent\",\"type\":\"SYSTEM\"}," +
                "{\"id\":\"Label_42\",\"name\":\"Projects/Blue\",\"type\":\"USER\"}]}";
        FakeConnection connection = new FakeConnection(new URL(GmailApi.LABELS_ENDPOINT), 200, response, null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(GmailApi.LABELS_ENDPOINT, url.toString());
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        List<GmailApi.Label> labels = api.listLabels();

        assertEquals("GET", connection.method);
        assertEquals("Bearer test-access-token", connection.headers.get("Authorization"));
        assertEquals(3, labels.size());
        assertEquals("INBOX", labels.get(0).id);
        assertEquals("SYSTEM", labels.get(0).type);
        assertEquals("Label_42", labels.get(2).id);
        assertEquals("Projects/Blue", labels.get(2).name);
        assertEquals("USER", labels.get(2).type);
        assertTrue(connection.disconnected);
    }

    @Test
    public void getMessageLabelsUsesMinimalReadOnlyMessageFormat() throws Exception {
        String endpoint = GmailApi.MESSAGES_ENDPOINT + "/opaque%2Fmessage?format=minimal";
        FakeConnection connection = new FakeConnection(new URL(endpoint), 200,
                "{\"id\":\"opaque/message\",\"labelIds\":[\"INBOX\",\"STARRED\",\"Label_42\"]}", null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(endpoint, url.toString());
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        List<String> labels = api.getMessageLabels("opaque/message");

        assertEquals("GET", connection.method);
        assertEquals("Bearer test-access-token", connection.headers.get("Authorization"));
        assertEquals(3, labels.size());
        assertEquals("INBOX", labels.get(0));
        assertEquals("STARRED", labels.get(1));
        assertEquals("Label_42", labels.get(2));
        assertTrue(connection.disconnected);
    }

    @Test
    public void listMessagesCanExplicitlyIncludeSpamAndTrash() throws Exception {
        String endpoint = GmailApi.MESSAGES_ENDPOINT +
                "?maxResults=100&q=in%3Aanywhere&includeSpamTrash=true";
        FakeConnection connection = new FakeConnection(new URL(endpoint), 200,
                "{\"messages\":[]}", null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(endpoint, url.toString());
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        api.listMessages("in:anywhere", null, 100, true);

        assertEquals("GET", connection.method);
        assertEquals("Bearer test-access-token", connection.headers.get("Authorization"));
        assertTrue(connection.disconnected);
    }

    @Test
    public void historyListPreservesPaginationAndCollapsesCurrentLabelChangesByMessage() throws Exception {
        String endpoint = GmailApi.HISTORY_ENDPOINT +
                "?startHistoryId=history-99&maxResults=50&pageToken=next%2Fpage+token";
        String response = "{\"history\":[" +
                "{\"id\":\"101\",\"messagesAdded\":[{\"message\":{\"id\":\"m1\",\"threadId\":\"t1\"}}]," +
                "\"labelsAdded\":[{\"message\":{\"id\":\"m1\"},\"labelIds\":[\"Label_42\"]}]," +
                "\"labelsRemoved\":[{\"message\":{\"id\":\"m2\"},\"labelIds\":[\"UNREAD\"]}]}," +
                "{\"id\":\"102\",\"messagesDeleted\":[{\"message\":{\"id\":\"m2\"}}]}]," +
                "\"nextPageToken\":\"history-page-2\",\"historyId\":\"history-120\"}";
        FakeConnection connection = new FakeConnection(new URL(endpoint), 200, response, null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(endpoint, url.toString());
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        GmailApi.HistoryPage page = api.listHistory("history-99", "next/page token", 50);

        assertEquals(2, page.changes.size());
        assertEquals("m1", page.changes.get(0).id);
        assertEquals("t1", page.changes.get(0).threadId);
        assertFalse(page.changes.get(0).deleted);
        assertEquals("m2", page.changes.get(1).id);
        assertTrue(page.changes.get(1).deleted);
        assertEquals("history-page-2", page.nextPageToken);
        assertEquals("history-120", page.historyId);
        assertTrue(connection.disconnected);
    }

    @Test
    public void pendingHistoryBatchRoundTripsLabelsAndOpaquePageMetadata() throws Exception {
        GmailApi.MessageChange change = new GmailApi.MessageChange("m-1", "t-1", false,
                true, java.util.Arrays.asList("INBOX", "Label_42"));
        GmailApi.HistoryPage original = new GmailApi.HistoryPage(
                java.util.Collections.singletonList(change), "history/page+2", "history-120");

        GmailApi.HistoryPage resumed = GmailApi.HistoryPage.fromPendingJson(original.toPendingJson());

        assertEquals("history/page+2", resumed.nextPageToken);
        assertEquals("history-120", resumed.historyId);
        assertEquals(1, resumed.changes.size());
        assertEquals("m-1", resumed.changes.get(0).id);
        assertEquals("t-1", resumed.changes.get(0).threadId);
        assertTrue(resumed.changes.get(0).labelsPresent);
        assertEquals(java.util.Arrays.asList("INBOX", "Label_42"), resumed.changes.get(0).labels);
    }

    @Test
    public void expiredHistoryCursorIsReportedForControlledFullResync() throws Exception {
        String endpoint = GmailApi.HISTORY_ENDPOINT + "?startHistoryId=stale&maxResults=100";
        FakeConnection connection = new FakeConnection(new URL(endpoint), 404, null,
                "{\"error\":{\"code\":404,\"message\":\"History not found\"}}");
        GmailApi api = new GmailApi("test-access-token", url -> connection,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        try {
            api.listHistory("stale", null, 100);
            fail("Expected expired Gmail history cursor");
        } catch (GmailApi.HistoryExpiredException ex) {
            assertTrue(ex.getMessage().contains("HTTP 404"));
        }
        assertTrue(connection.disconnected);
    }

    @Test
    public void invalidFullSyncPageTokenIsReportedForRestartFromFirstPage() throws Exception {
        String endpoint = GmailApi.MESSAGES_ENDPOINT +
                "?maxResults=100&q=in%3Aanywhere&includeSpamTrash=true&pageToken=invalid";
        FakeConnection connection = new FakeConnection(new URL(endpoint), 400, null,
                "{\"error\":{\"code\":400,\"message\":\"Invalid page token\"}}");
        GmailApi api = new GmailApi("test-access-token", url -> connection,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        try {
            api.listMessages("in:anywhere", "invalid", 100, true);
            fail("Expected invalid Gmail list page token");
        } catch (GmailApi.InvalidPageTokenException ex) {
            assertTrue(ex.getMessage().contains("HTTP 400"));
        }
        assertTrue(connection.disconnected);
    }

    @Test
    public void getMessageReadsStructuredMimeAndDoesNotMapGmailIdToUid() throws Exception {
        String encodedBody = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "Body".getBytes(StandardCharsets.UTF_8));
        String response = "{\"id\":\"opaque-gmail-id\",\"threadId\":\"gmail-thread\"," +
                "\"historyId\":\"history-42\",\"internalDate\":\"1727700000000\"," +
                "\"sizeEstimate\":321,\"labelIds\":[\"INBOX\",\"UNREAD\"]," +
                "\"payload\":{\"mimeType\":\"text/plain\",\"headers\":[" +
                "{\"name\":\"Subject\",\"value\":\"Native Gmail API test\"}," +
                "{\"name\":\"From\",\"value\":\"sender@example.com\"}]," +
                "\"body\":{\"size\":4,\"data\":\"" + encodedBody + "\"}}}";
        final FakeConnection[] opened = {null};
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertTrue(url.toString().startsWith(GmailApi.MESSAGES_ENDPOINT + "/opaque-gmail-id?format=full&fields="));
            opened[0] = new FakeConnection(url, 200, response, null);
            return opened[0];
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output),
                value -> Base64.getUrlDecoder().decode(value));

        GmailApi.ReceivedMessage received = api.getMessage("opaque-gmail-id");

        assertEquals("GET", opened[0].method);
        assertEquals("Bearer test-access-token", opened[0].headers.get("Authorization"));
        assertEquals("opaque-gmail-id", received.id);
        assertEquals("gmail-thread", received.threadId);
        assertEquals("history-42", received.historyId);
        assertEquals(1727700000000L, received.internalDate);
        assertEquals(321, received.sizeEstimate);
        assertEquals(2, received.labels.size());
        assertEquals("INBOX", received.labels.get(0));
        assertEquals(null, received.rawRfc822);
        assertTrue(received.bodyIncluded);
        assertEquals("Native Gmail API test", received.mimeMessage.getSubject());
        assertEquals("sender@example.com", ((InternetAddress) received.mimeMessage.getFrom()[0]).getAddress());
        assertFalse("Ordinary message/body sync must not request complete raw MIME",
                opened[0].getURL().toString().contains("format=raw"));
        assertTrue(opened[0].disconnected);
    }

    @Test
    public void rawMessageRequestReturnsTheCompleteBase64UrlDecodedMime() throws Exception {
        byte[] mime = ("From: sender@example.com\r\nSubject: full message\r\n" +
                "Content-Type: multipart/mixed; boundary=boundary\r\n\r\n" +
                "--boundary\r\nContent-Type: text/plain\r\n\r\nbody\r\n" +
                "--boundary\r\nContent-Type: application/octet-stream; name=\"file.bin\"\r\n" +
                "Content-Disposition: attachment; filename=\"file.bin\"\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\nAQIDBA==\r\n--boundary--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(mime);
        final FakeConnection[] opened = {null};
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertTrue(url.toString().startsWith(GmailApi.MESSAGES_ENDPOINT +
                    "/opaque-gmail-id?format=raw&fields="));
            opened[0] = new FakeConnection(url, 200,
                    "{\"id\":\"opaque-gmail-id\",\"raw\":\"" + encoded + "\"}", null);
            return opened[0];
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output),
                value -> Base64.getUrlDecoder().decode(value));

        assertArrayEquals(mime, api.getRawMessage("opaque-gmail-id"));
        assertEquals("Bearer test-access-token", opened[0].headers.get("Authorization"));
        assertTrue(opened[0].disconnected);
    }

    @Test
    public void receiveReportsNativeGmailApiErrorBody() throws Exception {
        String errorBody = "{\"error\":{\"code\":404,\"message\":\"not found\"}}";
        String endpoint = GmailApi.MESSAGES_ENDPOINT + "/gone?format=raw";
        FakeConnection connection = new FakeConnection(new URL(endpoint), 404, null, errorBody);
        GmailApi api = new GmailApi("test-access-token", url -> connection,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));

        try {
            api.getMessage("gone");
            fail("Expected Gmail API HTTP error");
        } catch (IOException ex) {
            assertTrue(ex.getMessage().contains("Gmail API get failed: HTTP 404"));
            assertTrue(ex.getMessage().contains(errorBody));
        }
        assertTrue(connection.disconnected);
    }

    @Test
    public void listMessagePageSizeIsValidatedBeforeNetworkAccess() throws Exception {
        GmailApi api = new GmailApi("test-access-token", url -> {
            throw new AssertionError("Network must not be opened");
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        try {
            api.listMessages(null, null, 501);
            fail("Expected invalid page-size error");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("between 1 and 500"));
        }
    }

    @Test
    public void modifyMessageUsesGmailLabelDeltaEndpointAndPreservesReturnedLabels() throws Exception {
        String endpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/modify";
        FakeConnection connection = new FakeConnection(new URL(endpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"STARRED\",\"Label_7\"]}", null);
        final int[] quota = {0};
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(endpoint, url.toString());
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        api.setQuotaUsageListener(units -> quota[0] += units);

        List<String> labels = api.modifyMessage("gmail-id",
                java.util.Collections.singletonList("Label_7"),
                java.util.Collections.singletonList("INBOX"));

        assertEquals(java.util.Arrays.asList("STARRED", "Label_7"), labels);
        assertEquals("POST", connection.method);
        assertEquals("Bearer test-access-token", connection.headers.get("Authorization"));
        assertEquals("application/json; charset=UTF-8", connection.headers.get("Content-Type"));
        assertEquals("{\"addLabelIds\":[\"Label_7\"],\"removeLabelIds\":[\"INBOX\"]}",
                new String(connection.request.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(GmailRateLimitBackoff.MESSAGES_MODIFY_QUOTA_UNITS, quota[0]);
        assertTrue(connection.disconnected);
    }

    @Test
    public void moveFromInboxAddsDestinationAndRemovesInboxWithoutRemovingOtherLabels() throws Exception {
        String endpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/modify";
        FakeConnection connection = new FakeConnection(new URL(endpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"STARRED\",\"Label_Keep\",\"Label_Target\"]}", null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(endpoint, url.toString());
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        GmailMailMutator.MovePlan move = GmailMailMutator.createMovePlan(
                EntityFolder.USER, "Label_Target", EntityFolder.INBOX,
                "INBOX", false, java.util.Arrays.asList("INBOX", "STARRED", "Label_Keep"));
        GmailMailMutator.Plan plan = new GmailMailMutator.Plan();
        plan.add = move.add;
        plan.remove = move.remove;

        List<String> labels = GmailMailMutator.applyAndVerify(api, plan,
                "gmail-id", new FakeOperationLifecycle());

        assertEquals(java.util.Arrays.asList("STARRED", "Label_Keep", "Label_Target"), labels);
        assertEquals("{\"addLabelIds\":[\"Label_Target\"],\"removeLabelIds\":[\"INBOX\"]}",
                new String(connection.request.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void trashMovesMessageThenRemovesEveryOtherGmailLabel() throws Exception {
        String trashEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/trash";
        String modifyEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/modify";
        FakeConnection trash = new FakeConnection(new URL(trashEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"TRASH\",\"INBOX\",\"Label_7\",\"STARRED\",\"SENT\"]}", null);
        FakeConnection cleanup = new FakeConnection(new URL(modifyEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"TRASH\",\"SENT\"]}", null);
        GmailApi api = new GmailApi("test-access-token", url -> {
            if (trashEndpoint.equals(url.toString()))
                return trash;
            assertEquals(modifyEndpoint, url.toString());
            return cleanup;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        GmailMailMutator.MovePlan move = GmailMailMutator.createMovePlan(
                EntityFolder.TRASH, "TRASH", EntityFolder.INBOX,
                "INBOX", false, java.util.Arrays.asList("INBOX", "Label_7", "STARRED"));
        GmailMailMutator.Plan plan = new GmailMailMutator.Plan();
        plan.trash = move.trash;
        plan.clearOtherLabels = move.clearOtherLabels;
        plan.add = move.add;
        FakeOperationLifecycle lifecycle = new FakeOperationLifecycle();

        List<String> labels = GmailMailMutator.applyAndVerify(api, plan, "gmail-id", lifecycle);

        assertEquals(java.util.Arrays.asList("TRASH", "SENT"), labels);
        assertTrue(plan.isSatisfied(labels));
        assertFalse(plan.isSatisfied(java.util.Arrays.asList("TRASH", "Label_7")));
        assertEquals("POST", trash.method);
        assertEquals("{}", new String(trash.request.toByteArray(), StandardCharsets.UTF_8));
        assertEquals("{\"removeLabelIds\":[\"INBOX\",\"Label_7\",\"STARRED\"]}",
                new String(cleanup.request.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void trashAndRestoreUseNativeEndpointsWithoutOverwritingMemberships() throws Exception {
        FakeConnection trash = new FakeConnection(
                new URL(GmailApi.MESSAGES_ENDPOINT + "/gmail-id/trash"), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"TRASH\",\"Label_7\"]}", null);
        GmailApi api = new GmailApi("test-access-token", url -> trash,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        assertEquals(java.util.Arrays.asList("TRASH", "Label_7"), api.trashMessage("gmail-id"));
        assertEquals("POST", trash.method);
        assertEquals("{}", new String(trash.request.toByteArray(), StandardCharsets.UTF_8));

        FakeConnection untrash = new FakeConnection(
                new URL(GmailApi.MESSAGES_ENDPOINT + "/gmail-id/untrash"), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"INBOX\",\"Label_7\"]}", null);
        GmailApi restore = new GmailApi("test-access-token", url -> untrash,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        assertEquals(java.util.Arrays.asList("INBOX", "Label_7"), restore.untrashMessage("gmail-id"));
        assertEquals("POST", untrash.method);
        assertEquals("{}", new String(untrash.request.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void permanentDeleteUsesOnlyTheMessageDeleteEndpointAndHandlesTrashRecovery404() throws Exception {
        String endpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id";
        FakeConnection connection = new FakeConnection(new URL(endpoint), 204, "", null);
        final int[] quota = {0};
        GmailApi api = new GmailApi("test-access-token", url -> {
            assertEquals(endpoint, url.toString());
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        api.setQuotaUsageListener(units -> quota[0] += units);

        api.deleteMessage("gmail-id");

        assertEquals("DELETE", connection.method);
        assertEquals(0, connection.request.size());
        assertEquals(GmailRateLimitBackoff.MESSAGES_DELETE_QUOTA_UNITS, quota[0]);
        assertTrue(connection.disconnected);

        FakeConnection missing = new FakeConnection(new URL(endpoint), 404, null,
                "{\"error\":{\"code\":404,\"message\":\"not found\"}}");
        GmailApi retry = new GmailApi("test-access-token", url -> missing,
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
        try {
            retry.deleteMessage("gmail-id");
            fail("Expected a recoverable already-deleted response");
        } catch (GmailApi.MessageUnavailableException expected) {
            assertTrue(expected.getMessage().contains("HTTP 404"));
        }
        assertTrue(missing.disconnected);
    }

    @Test
    public void sentDeleteUsesTrashAndLabelCleanupPreservingSentWithoutCallingMessagesDelete() throws Exception {
        String trashEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/trash";
        String modifyEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/modify";
        FakeConnection trash = new FakeConnection(new URL(trashEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"TRASH\",\"INBOX\",\"Label_Custom\",\"STARRED\",\"UNREAD\",\"SENT\"]}", null);
        FakeConnection cleanup = new FakeConnection(new URL(modifyEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"TRASH\",\"SENT\"]}", null);
        List<String> endpoints = java.util.Arrays.asList(trashEndpoint, modifyEndpoint);
        GmailApi api = scriptedApi(endpoints, java.util.Arrays.asList(trash, cleanup));
        GmailMailMutator.MovePlan sentToTrash = GmailMailMutator.createMovePlan(
                EntityFolder.TRASH, "TRASH", EntityFolder.SENT, "SENT", false,
                java.util.Arrays.asList("SENT", "INBOX", "Label_Custom", "STARRED", "UNREAD"));
        assertTrue(sentToTrash.trash);
        assertTrue(sentToTrash.remove.isEmpty());
        GmailMailMutator.Plan softDelete = new GmailMailMutator.Plan();
        softDelete.trash = sentToTrash.trash;
        softDelete.clearOtherLabels = sentToTrash.clearOtherLabels;
        softDelete.changesFolder = true;
        softDelete.add = sentToTrash.add;
        softDelete.remove = sentToTrash.remove;
        FakeOperationLifecycle lifecycle = new FakeOperationLifecycle();

        List<String> labels = GmailMailMutator.applyAndVerify(api, softDelete, "gmail-id", lifecycle);
        assertTrue(softDelete.isSatisfied(labels));
        assertEquals(java.util.Arrays.asList("TRASH", "SENT"), labels);
        assertEquals("POST", trash.method);
        assertEquals("POST", cleanup.method);
        assertEquals("{}", new String(trash.request.toByteArray(), StandardCharsets.UTF_8));
        assertEquals("{\"removeLabelIds\":[\"INBOX\",\"Label_Custom\",\"STARRED\",\"UNREAD\"]}",
                new String(cleanup.request.toByteArray(), StandardCharsets.UTF_8));
        assertFalse("Soft Delete must never call users.messages.delete", endpoints.contains(
                GmailApi.MESSAGES_ENDPOINT + "/gmail-id"));
        assertEquals(2, endpoints.size());
    }

    @Test
    public void explicitPermanentDeleteFromInboxPreparesAndConfirmsTrashBeforeMessagesDelete() throws Exception {
        String getEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id?format=minimal";
        String modifyEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/modify";
        String deleteEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id";
        FakeConnection preflight = new FakeConnection(new URL(getEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"INBOX\",\"Label_Custom\",\"STARRED\",\"UNREAD\",\"SENT\"]}", null);
        FakeConnection modify = new FakeConnection(new URL(modifyEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"TRASH\",\"SENT\"]}", null);
        FakeConnection delete = new FakeConnection(new URL(deleteEndpoint), 204, "", null);
        List<String> endpoints = java.util.Arrays.asList(getEndpoint, modifyEndpoint, deleteEndpoint);
        GmailApi api = scriptedApi(endpoints, java.util.Arrays.asList(preflight, modify, delete));
        final int[] completed = {0};

        GmailMailMutator.permanentlyDeleteAndComplete(api, "gmail-id", () -> completed[0]++);

        assertEquals(java.util.Arrays.asList("GET", "POST", "DELETE"),
                java.util.Arrays.asList(preflight.method, modify.method, delete.method));
        assertEquals("{\"addLabelIds\":[\"TRASH\"],\"removeLabelIds\":[\"INBOX\",\"Label_Custom\",\"STARRED\",\"UNREAD\"]}",
                new String(modify.request.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(1, completed[0]);
        assertTrue(preflight.disconnected && modify.disconnected && delete.disconnected);
    }

    @Test
    public void explicitPermanentDeleteFromAlreadyCleanTrashDoesNotSendRedundantModify() throws Exception {
        String getEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id?format=minimal";
        String deleteEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id";
        FakeConnection preflight = new FakeConnection(new URL(getEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"TRASH\",\"SENT\"]}", null);
        FakeConnection delete = new FakeConnection(new URL(deleteEndpoint), 204, "", null);
        List<String> endpoints = java.util.Arrays.asList(getEndpoint, deleteEndpoint);
        GmailApi api = scriptedApi(endpoints, java.util.Arrays.asList(preflight, delete));
        final int[] completed = {0};

        GmailMailMutator.permanentlyDeleteAndComplete(api, "gmail-id", () -> completed[0]++);

        assertEquals("GET", preflight.method);
        assertEquals("DELETE", delete.method);
        assertEquals(0, delete.request.size());
        assertEquals(1, completed[0]);
        assertEquals(2, endpoints.size());
    }

    @Test
    public void failedModifyMustBeReconciledAndCannotProceedToDeleteUnlessTrashIsConfirmed() throws Exception {
        String getEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id?format=minimal";
        String modifyEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/modify";
        FakeConnection preflight = new FakeConnection(new URL(getEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"INBOX\",\"Label_Custom\"]}", null);
        FakeConnection unknownModify = new FakeConnection(new URL(modifyEndpoint), 500, null,
                "{\"error\":{\"code\":500,\"message\":\"unknown modify outcome\"}}");
        FakeConnection readback = new FakeConnection(new URL(getEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"INBOX\",\"Label_Custom\"]}", null);
        List<String> endpoints = java.util.Arrays.asList(getEndpoint, modifyEndpoint, getEndpoint);
        GmailApi api = scriptedApi(endpoints, java.util.Arrays.asList(preflight, unknownModify, readback));
        final int[] completed = {0};

        try {
            GmailMailMutator.permanentlyDeleteAndComplete(api, "gmail-id", () -> completed[0]++);
            fail("Unconfirmed Trash preparation must stop before permanent delete");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("permanent delete was not attempted"));
        }
        assertEquals(java.util.Arrays.asList("GET", "POST", "GET"),
                java.util.Arrays.asList(preflight.method, unknownModify.method, readback.method));
        assertEquals(0, completed[0]);
        assertEquals(3, endpoints.size());
    }

    @Test
    public void modifyUnknownOutcomeCanContinueOnlyAfterReadbackConfirmsCleanTrash() throws Exception {
        String getEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id?format=minimal";
        String modifyEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id/modify";
        String deleteEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id";
        FakeConnection preflight = new FakeConnection(new URL(getEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"INBOX\"]}", null);
        FakeConnection modify = new FakeConnection(new URL(modifyEndpoint), 500, null,
                "{\"error\":{\"code\":500,\"message\":\"lost modify response\"}}");
        FakeConnection readback = new FakeConnection(new URL(getEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"TRASH\"]}", null);
        FakeConnection delete = new FakeConnection(new URL(deleteEndpoint), 204, "", null);
        List<String> endpoints = java.util.Arrays.asList(getEndpoint, modifyEndpoint, getEndpoint, deleteEndpoint);
        GmailApi api = scriptedApi(endpoints, java.util.Arrays.asList(preflight, modify, readback, delete));
        final int[] completed = {0};

        GmailMailMutator.permanentlyDeleteAndComplete(api, "gmail-id", () -> completed[0]++);

        assertEquals(java.util.Arrays.asList("GET", "POST", "GET", "DELETE"),
                java.util.Arrays.asList(preflight.method, modify.method, readback.method, delete.method));
        assertEquals(1, completed[0]);
    }

    @Test
    public void unknownDeleteResponseQuarantinesAndNeverRunsLocalCompletionCallback() throws Exception {
        String getEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id?format=minimal";
        String deleteEndpoint = GmailApi.MESSAGES_ENDPOINT + "/gmail-id";
        FakeConnection preflight = new FakeConnection(new URL(getEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"TRASH\",\"SENT\"]}", null);
        FakeConnection delete = new FakeConnection(new URL(deleteEndpoint), 500, null,
                "{\"error\":{\"code\":500,\"message\":\"lost delete response\"}}");
        FakeConnection readback = new FakeConnection(new URL(getEndpoint), 200,
                "{\"id\":\"gmail-id\",\"labelIds\":[\"TRASH\",\"SENT\"]}", null);
        List<String> endpoints = java.util.Arrays.asList(getEndpoint, deleteEndpoint, getEndpoint);
        GmailApi api = scriptedApi(endpoints, java.util.Arrays.asList(preflight, delete, readback));
        final int[] completed = {0};

        try {
            GmailMailMutator.permanentlyDeleteAndComplete(api, "gmail-id", () -> completed[0]++);
            fail("Unknown delete response must quarantine the operation");
        } catch (GmailMailMutator.PermanentDeleteOutcomeUnknownException expected) {
            assertEquals(java.util.Arrays.asList("TRASH", "SENT"), expected.labels);
        }
        assertEquals(java.util.Arrays.asList("GET", "DELETE", "GET"),
                java.util.Arrays.asList(preflight.method, delete.method, readback.method));
        assertEquals(0, completed[0]);
    }

    private static GmailApi scriptedApi(List<String> expectedEndpoints,
                                       List<FakeConnection> connections) {
        final int[] index = {0};
        return new GmailApi("test-access-token", url -> {
            assertTrue(index[0] < expectedEndpoints.size());
            assertEquals(expectedEndpoints.get(index[0]), url.toString());
            return connections.get(index[0]++);
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output));
    }

    private static MimeMessage createMessage() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(MimeMessage.RecipientType.TO,
                new InternetAddress("recipient@example.com"));
        message.setSubject("Native Gmail API test", "UTF-8");
        message.setText("MIME body stays in FairEmail's composed representation.", "UTF-8");
        message.saveChanges();
        return message;
    }

    private static String messagePageResponse(String nextPageToken, String... ids) {
        StringBuilder json = new StringBuilder("{\"messages\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0)
                json.append(',');
            json.append("{\"id\":\"").append(ids[i]).append("\"}");
        }
        json.append(']');
        if (nextPageToken != null)
            json.append(",\"nextPageToken\":\"").append(nextPageToken).append('"');
        return json.append('}').toString();
    }

    private static List<String> messageIds(GmailApi.MessagePage page) {
        List<String> ids = new java.util.ArrayList<>();
        for (GmailApi.MessageReference message : page.messages)
            ids.add(message.id);
        return ids;
    }

    private static EntityOperation sendOperation(long id) {
        EntityOperation operation = new EntityOperation();
        operation.id = id;
        operation.name = EntityOperation.SEND;
        return operation;
    }

    private static class FakeOperationLifecycle implements GmailMailMutator.OperationLifecycle {
        String state;
        boolean completed;
        List<String> completedLabels;

        @Override
        public void setState(String state) {
            this.state = state;
        }

        @Override
        public void complete(List<String> labels) {
            completed = true;
            completedLabels = new java.util.ArrayList<>(labels);
        }
    }

    private static class FakeFailureLifecycle implements GmailMailMutator.FailureLifecycle {
        List<String> labels;
        boolean pending = true;
        boolean pendingCleared;
        boolean missing;

        FakeFailureLifecycle(List<String> labels) {
            this.labels = new java.util.ArrayList<>(labels);
        }

        @Override
        public void reconcile(List<String> labels) {
            this.labels = new java.util.ArrayList<>(labels);
            pending = false;
            pendingCleared = true;
        }

        @Override
        public void clearPending() {
            pending = false;
            pendingCleared = true;
        }

        @Override
        public void missing() {
            missing = true;
            pending = false;
        }

        @Override
        public void readbackFailed(Throwable ex) {
            // Keep the last confirmed label snapshot unchanged.
        }
    }

    @Test
    public void structuredMessageReadSelectsBodyDataOnlyWhenBodyIsRequested() throws Exception {
        List<String> requested = new java.util.ArrayList<>();
        String json = "{\"id\":\"message\",\"labelIds\":[\"INBOX\"]," +
                "\"payload\":{\"mimeType\":\"text/plain\",\"body\":{\"size\":4,\"data\":\"Ym9keQ\"}}}";
        GmailApi api = new GmailApi("token", url -> {
            requested.add(url.toString());
            return new FakeConnection(url, 200, json, null);
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output),
                value -> Base64.getUrlDecoder().decode(value));

        GmailApi.ReceivedMessage metadata = api.getMessage("message", false);
        GmailApi.ReceivedMessage body = api.getMessage("message", true);

        assertEquals(2, requested.size());
        String metadataQuery = java.net.URLDecoder.decode(new URL(requested.get(0)).getQuery(), "UTF-8");
        String bodyQuery = java.net.URLDecoder.decode(new URL(requested.get(1)).getQuery(), "UTF-8");
        assertTrue(metadataQuery.startsWith("format=full&fields="));
        assertFalse(metadataQuery.contains("body(size,attachmentId,data)"));
        assertTrue(bodyQuery.contains("body(size,attachmentId,data)"));
        assertFalse(metadata.bodyIncluded);
        assertTrue(body.bodyIncluded);
        assertTrue("No attachment-byte URL is called by message sync", requested.stream()
                .noneMatch(value -> value.contains("/attachments/")));
    }

    @Test
    public void textBodyAttachmentIdIsFetchedAndRequiredBeforeBodyIsMarkedComplete() throws Exception {
        List<String> requested = new java.util.ArrayList<>();
        String messageEndpoint = GmailApi.MESSAGES_ENDPOINT + "/message?";
        String bodyEndpoint = GmailApi.MESSAGES_ENDPOINT + "/message/attachments/body-part";
        String messageJson = "{\"id\":\"message\",\"payload\":{" +
                "\"mimeType\":\"text/plain\",\"body\":{" +
                "\"size\":4,\"attachmentId\":\"body-part\"}}}";
        byte[] expected = "body".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(expected);
        GmailApi api = new GmailApi("token", url -> {
            requested.add(url.toString());
            if (url.toString().equals(bodyEndpoint))
                return new FakeConnection(url, 200, "{\"size\":4,\"data\":\"" + encoded + "\"}", null);
            assertTrue(url.toString(), url.toString().startsWith(messageEndpoint));
            return new FakeConnection(url, 200, messageJson, null);
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output),
                value -> Base64.getUrlDecoder().decode(value));

        GmailApi.ReceivedMessage received = api.getMessage("message", true);

        assertTrue(received.bodyIncluded);
        assertEquals(1, received.bodyParts);
        assertEquals(1, received.fetchedBodyParts);
        assertEquals(0, received.incompleteBodyParts);
        assertEquals(2, requested.size());
        assertTrue(requested.get(0).startsWith(messageEndpoint));
        assertEquals(bodyEndpoint, requested.get(1));
        try (InputStream body = received.mimeMessage.getInputStream()) {
            assertArrayEquals(expected, body.readAllBytes());
        }
    }

    @Test
    public void missingNonemptyGmailTextPartDoesNotMarkReceivedBodyComplete() throws Exception {
        String json = "{\"id\":\"message\",\"payload\":{" +
                "\"mimeType\":\"text/html\",\"body\":{\"size\":12}}}";
        GmailApi api = new GmailApi("token", url -> new FakeConnection(url, 200, json, null),
                output -> Base64.getUrlEncoder().withoutPadding().wrap(output),
                value -> Base64.getUrlDecoder().decode(value));

        GmailApi.ReceivedMessage received = api.getMessage("message", true);

        assertFalse(received.bodyIncluded);
        assertEquals(1, received.bodyParts);
        assertEquals(1, received.incompleteBodyParts);
    }

    @Test
    public void selectedGmailAttachmentRequestUsesItsAttachmentId() throws Exception {
        String endpoint = GmailApi.MESSAGES_ENDPOINT + "/message/attachments/attachment";
        byte[] expected = "selected".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(expected);
        FakeConnection connection = new FakeConnection(new URL(endpoint), 200,
                "{\"size\":8,\"data\":\"" + encoded + "\"}", null);
        GmailApi api = new GmailApi("token", url -> {
            assertEquals(endpoint, url.toString());
            return connection;
        }, output -> Base64.getUrlEncoder().withoutPadding().wrap(output),
                value -> Base64.getUrlDecoder().decode(value));

        assertArrayEquals(expected, api.getAttachmentBytes("message", "attachment"));
        assertEquals("GET", connection.method);
    }

    private static class FakeConnection extends HttpsURLConnection {
        final Map<String, String> headers = new HashMap<>();
        final Map<String, String> responseHeaders = new HashMap<>();
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        final int status;
        final String response;
        final String error;
        String method;
        boolean disconnected;

        FakeConnection(URL url, int status, String response, String error) {
            super(url);
            this.status = status;
            this.response = response;
            this.error = error;
        }

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void setRequestMethod(String method) throws ProtocolException {
            this.method = method;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public void setRequestProperty(String key, String value) {
            headers.put(key, value);
        }

        @Override
        public String getRequestProperty(String key) {
            return headers.get(key);
        }

        @Override
        public OutputStream getOutputStream() {
            return request;
        }

        @Override
        public int getResponseCode() throws IOException {
            return status;
        }

        @Override
        public String getResponseMessage() {
            return status == 200 ? "OK" : "Forbidden";
        }

        @Override
        public String getHeaderField(String name) {
            return responseHeaders.get(name);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            return (error == null ? null : new ByteArrayInputStream(error.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public String getCipherSuite() {
            return null;
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
            return null;
        }

        @Override
        public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
            return null;
        }

        @Override
        public Principal getLocalPrincipal() {
            return null;
        }
    }
}
