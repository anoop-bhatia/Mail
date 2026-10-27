package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Base64OutputStream;

import androidx.preference.PreferenceManager;

import net.openid.appauth.AuthState;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.net.ssl.HttpsURLConnection;

public class MicrosoftGraph {
    static final int GRAPH_TIMEOUT = 20; // seconds
    // Large MIME requests can exceed two minutes; retain a finite queue bound without a body-size cap.
    static final long GRAPH_SEND_TIMEOUT = GraphTimeouts.MAX_OVERALL_SEND_MILLIS;
    static final String GRAPH_ENDPOINT = "https://graph.microsoft.com/v1.0/me/";

    static boolean isSendAcceptedStatus(int status) {
        return (status == HttpsURLConnection.HTTP_ACCEPTED);
    }

    static String getDirectSendUrl(String replySourceGraphId) {
        return (replySourceGraphId == null || replySourceGraphId.trim().length() == 0
                ? GRAPH_ENDPOINT + "sendMail"
                : graphMessageUrl(replySourceGraphId) + "/reply");
    }

    private static String graphMessageUrl(String messageId) {
        return GRAPH_ENDPOINT + "messages/" + GraphMailApi.segment(messageId);
    }

    static void send(Context context, EntityIdentity ident, MimeMessage imessage,
                     long operationId, long messageId, String replySourceGraphId)
            throws IOException, JSONException, MessagingException {
        DB db = DB.getInstance(context);
        boolean accepted = false;

        try {
            db.identity().setIdentityState(ident.id, "connecting");

            long refreshStart = System.currentTimeMillis();
            logSendStage(context, operationId, messageId, "refresh-start", null, 0);
            AuthState authState = AuthState.jsonDeserialize(ident.password);
            int graphAuthType = (ident.isGraphApi()
                    ? ServiceAuthenticator.AUTH_TYPE_GRAPH_API : ident.auth_type);
            ServiceAuthenticator.OAuthRefresh(context, ident.provider, graphAuthType, ident.user, authState, true);
            logSendStage(context, operationId, messageId, "refresh-complete", null,
                    System.currentTimeMillis() - refreshStart);

            String newPassword = authState.jsonSerializeString();
            if (!Objects.equals(ident.password, newPassword))
                db.identity().setIdentityPassword(ident.id, newPassword);

            db.identity().setIdentityState(ident.id, "connected");

            final String accessToken = authState.getAccessToken();
            final GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
            final GraphAttachmentPlan.Plan attachmentPlan = (ident.isGraphApi()
                    ? GraphAttachmentPlan.prepare(imessage) : null);
            if (attachmentPlan != null && attachmentPlan.directFallback)
                logSendStage(context, operationId, messageId, "large-attachment-direct-fallback", null, 0);
            final long sendTimeout = (attachmentPlan != null && attachmentPlan.requiresUploadSessions()
                    ? GraphAttachmentUpload.overallTimeoutMillis()
                    : GRAPH_SEND_TIMEOUT);
            GraphSendAttempt.execute(() -> {
                if (attachmentPlan != null && attachmentPlan.requiresUploadSessions())
                    GraphDraftFlow.send(new GraphDraftTransport(context, accessToken, progress,
                                    operationId, messageId), replySourceGraphId, imessage, attachmentPlan);
                else
                    sendDirect(context, accessToken, imessage, operationId, messageId, progress,
                            replySourceGraphId);
                return null;
            }, sendTimeout, progress);
            accepted = true;
        } finally {
            try {
                db.identity().setIdentityState(ident.id, null);
            } catch (RuntimeException ex) {
                Log.w("Graph send stage=identity-state-clear-failed op=" + operationId +
                        " message=" + messageId + " accepted=" + accepted);
            }
        }
    }

    private static void sendDirect(Context context, String accessToken, MimeMessage message,
                                   long operationId, long messageId,
                                   GraphSendAttempt.Progress progress, String replySourceGraphId)
            throws IOException, MessagingException {
        // https://learn.microsoft.com/en-us/graph/api/user-sendmail?view=graph-rest-1.0
        // https://learn.microsoft.com/en-us/graph/api/message-reply?view=graph-rest-1.0
        long requestStart = System.currentTimeMillis();
        URL url = new URL(getDirectSendUrl(replySourceGraphId));
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        progress.setConnection(connection);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setReadTimeout(GraphTimeouts.DIRECT_SEND_RESPONSE_MILLIS);
        connection.setConnectTimeout(GraphTimeouts.CONNECT_MILLIS);
        ConnectionHelper.setUserAgent(context, connection);
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setRequestProperty("Content-Type", "text/plain");

        logSendStage(context, operationId, messageId, "connect-start", null, 0);
        try {
            connection.connect();
            progress.setPhase(GraphSendAttempt.Phase.UPLOADING);
            logSendStage(context, operationId, messageId, "upload-start", null,
                    System.currentTimeMillis() - requestStart);
            try (OutputStream out = new Base64OutputStream(connection.getOutputStream(),
                    Base64.DEFAULT | Base64.NO_CLOSE)) {
                message.writeTo(out);
            }

            progress.setPhase(GraphSendAttempt.Phase.WAITING_RESPONSE);
            logSendStage(context, operationId, messageId, "response-wait", null,
                    System.currentTimeMillis() - requestStart);
            int status = connection.getResponseCode();
            progress.setStatus(status);
            if (isSendAcceptedStatus(status))
                progress.setPhase(GraphSendAttempt.Phase.ACCEPTED);
            logSendStage(context, operationId, messageId, "response", status,
                    System.currentTimeMillis() - requestStart);
            if (!isSendAcceptedStatus(status))
                throw new IOException("Microsoft Graph HTTP " + status);
        } finally {
            connection.disconnect();
        }
    }

    private static final class GraphDraftTransport implements GraphDraftFlow.Transport {
        private final Context context;
        private final String accessToken;
        private final GraphSendAttempt.Progress progress;
        private final long operationId;
        private final long messageId;
        private final long started = System.currentTimeMillis();

        GraphDraftTransport(Context context, String accessToken, GraphSendAttempt.Progress progress,
                            long operationId, long messageId) {
            this.context = context;
            this.accessToken = accessToken;
            this.progress = progress;
            this.operationId = operationId;
            this.messageId = messageId;
        }

        @Override
        public boolean isCancelled() {
            return progress.isCancelled();
        }

        @Override
        public String createDraft(MimeMessage message) throws IOException, MessagingException {
            // https://learn.microsoft.com/en-us/graph/api/user-post-messages?view=graph-rest-1.0
            return createDraftAt(GRAPH_ENDPOINT + "messages", message,
                    "draft-create-upload", "draft-created");
        }

        @Override
        public String createReplyDraft(String sourceMessageId, MimeMessage message)
                throws IOException, MessagingException {
            // https://learn.microsoft.com/en-us/graph/api/message-createreply?view=graph-rest-1.0
            return createDraftAt(graphMessageUrl(sourceMessageId) + "/createReply", message,
                    "reply-draft-create-upload", "reply-draft-created");
        }

        private String createDraftAt(String endpoint, MimeMessage message,
                                     String uploadStage, String createdStage)
                throws IOException, MessagingException {
            progress.setPhase(GraphSendAttempt.Phase.PREPARING_DRAFT);
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpsURLConnection connection = openGraphConnection(context,
                        new URL(endpoint), "POST", accessToken,
                        GraphTimeouts.DRAFT_CREATE_RESPONSE_MILLIS, progress);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "text/plain");
                try {
                    connection.connect();
                    logSendStage(context, operationId, messageId, uploadStage, null,
                            System.currentTimeMillis() - started);
                    try (OutputStream out = new Base64OutputStream(connection.getOutputStream(),
                            Base64.DEFAULT | Base64.NO_CLOSE)) {
                        message.writeTo(out);
                    }
                    int status = connection.getResponseCode();
                    if (status == 201) {
                        JSONObject draft = new JSONObject(Helper.readStream(connection.getInputStream()));
                        String id = draft.optString("id", null);
                        if (TextUtils.isEmpty(id))
                            throw new IOException("Microsoft Graph draft response did not include a message ID");
                        logSendStage(context, operationId, messageId, createdStage, status,
                                System.currentTimeMillis() - started);
                        return id;
                    }
                    if (status == 429 && attempt < 2) {
                        waitForRetry(connection.getHeaderField("Retry-After"));
                        continue;
                    }
                    throw new IOException("Microsoft Graph draft HTTP " + status);
                } catch (org.json.JSONException ex) {
                    throw new IOException("Invalid Microsoft Graph draft response");
                } finally {
                    connection.disconnect();
                }
            }
            throw new IOException("Microsoft Graph draft throttled");
        }

        @Override
        public void uploadAttachment(String draftId, GraphAttachmentPlan.Attachment attachment)
                throws IOException {
            progress.setPhase(GraphSendAttempt.Phase.UPLOADING_ATTACHMENTS);
            logSendStage(context, operationId, messageId, "attachment-upload-start", null,
                    System.currentTimeMillis() - started);
            GraphAttachmentUpload.upload(this, draftId, attachment);
            logSendStage(context, operationId, messageId, "attachment-upload-complete", 201,
                    System.currentTimeMillis() - started);
        }

        @Override
        public void sendDraft(String draftId) throws IOException {
            // https://learn.microsoft.com/en-us/graph/api/message-send?view=graph-rest-1.0
            for (int attempt = 0; attempt < 3; attempt++) {
                progress.setPhase(GraphSendAttempt.Phase.WAITING_RESPONSE);
                progress.setStatus(-1);
                HttpsURLConnection connection = openGraphConnection(context,
                        new URL(graphMessageUrl(draftId) + "/send"), "POST", accessToken,
                        GraphTimeouts.DRAFT_SEND_RESPONSE_MILLIS, progress);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
                try {
                    connection.connect();
                    try (OutputStream out = connection.getOutputStream()) {
                        out.flush();
                    }
                    int status = connection.getResponseCode();
                    progress.setStatus(status);
                    if (isSendAcceptedStatus(status)) {
                        progress.setPhase(GraphSendAttempt.Phase.ACCEPTED);
                        logSendStage(context, operationId, messageId, "draft-send-accepted", status,
                                System.currentTimeMillis() - started);
                        return;
                    }
                    if (status == 429 && attempt < 2) {
                        progress.setPhase(GraphSendAttempt.Phase.PREPARING_DRAFT);
                        progress.setStatus(-1);
                        waitForRetry(connection.getHeaderField("Retry-After"));
                        continue;
                    }
                    logSendStage(context, operationId, messageId, "draft-send-response", status,
                            System.currentTimeMillis() - started);
                    throw new IOException("Microsoft Graph draft send HTTP " + status);
                } finally {
                    connection.disconnect();
                }
            }
            throw new IOException("Microsoft Graph draft send throttled");
        }

        @Override
        public void deleteDraft(String draftId) throws IOException {
            HttpsURLConnection connection = openGraphConnection(context,
                    new URL(graphMessageUrl(draftId)), "DELETE", accessToken,
                    GraphTimeouts.DRAFT_DELETE_RESPONSE_MILLIS, progress);
            try {
                int status = connection.getResponseCode();
                if (status != 204 && status != 200 && status != 404)
                    throw new IOException("Microsoft Graph draft cleanup HTTP " + status);
            } finally {
                connection.disconnect();
            }
        }

        @Override
        public GraphAttachmentUpload.Session createSession(String draftId,
                GraphAttachmentPlan.Attachment attachment) throws IOException {
            // https://learn.microsoft.com/en-us/graph/api/attachment-createuploadsession?view=graph-rest-1.0
            JSONObject item = new JSONObject();
            JSONObject body = new JSONObject();
            try {
                item.put("attachmentType", "file");
                item.put("name", attachment.name);
                item.put("size", attachment.size);
                item.put("contentType", attachment.contentType);
                if (attachment.inline)
                    item.put("isInline", true);
                if (attachment.contentId != null)
                    item.put("contentId", attachment.contentId);
                body.put("AttachmentItem", item);
            } catch (org.json.JSONException ex) {
                throw new IOException("Unable to prepare Microsoft Graph attachment session");
            }

            byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(graphMessageUrl(draftId) + "/attachments/createUploadSession");
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpsURLConnection connection = openGraphConnection(context, url, "POST", accessToken,
                        GraphTimeouts.UPLOAD_SESSION_CREATE_RESPONSE_MILLIS, progress);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(request.length);
                connection.setRequestProperty("Content-Type", "application/json");
                try {
                    connection.connect();
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(request);
                    }
                    int status = connection.getResponseCode();
                    if (status == 201)
                        return parseUploadSession(Helper.readStream(connection.getInputStream()), null);
                    if (status == 429 && attempt < 2) {
                        waitForRetry(connection.getHeaderField("Retry-After"));
                        continue;
                    }
                    throw new IOException("Microsoft Graph attachment session HTTP " + status);
                } finally {
                    connection.disconnect();
                }
            }
            throw new IOException("Microsoft Graph attachment session throttled");
        }

        @Override
        public GraphAttachmentUpload.Session getUploadStatus(String opaqueUploadUrl) throws IOException {
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpsURLConnection connection = openUploadConnection(context, opaqueUploadUrl, "GET",
                        GraphTimeouts.UPLOAD_SESSION_STATUS_RESPONSE_MILLIS, progress);
                try {
                    int status = connection.getResponseCode();
                    if (status == 200)
                        return parseUploadSession(Helper.readStream(connection.getInputStream()), opaqueUploadUrl);
                    if ((status == 429 || status >= 500 && status <= 599) && attempt < 2) {
                        waitForRetry(connection.getHeaderField("Retry-After"));
                        continue;
                    }
                    throw new GraphAttachmentUpload.HttpFailure(status,
                            connection.getHeaderField("Retry-After"));
                } finally {
                    connection.disconnect();
                }
            }
            throw new IOException("Microsoft Graph attachment upload status unavailable");
        }

        @Override
        public GraphAttachmentUpload.PutResult putRange(String opaqueUploadUrl, File file,
                GraphAttachmentUpload.Range range, long totalSize) throws IOException {
            int length = (int) range.length();
            HttpsURLConnection connection = openUploadConnection(context, opaqueUploadUrl, "PUT",
                    GraphTimeouts.UPLOAD_CHUNK_RESPONSE_MILLIS, progress);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(length);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("Content-Range",
                    GraphAttachmentUpload.contentRange(range, totalSize));
            try {
                connection.connect();
                try (RandomAccessFile input = new RandomAccessFile(file, "r");
                     OutputStream out = connection.getOutputStream()) {
                    input.seek(range.start);
                    byte[] buffer = new byte[64 * 1024];
                    long remaining = range.length();
                    while (remaining > 0) {
                        int wanted = (int) Math.min(buffer.length, remaining);
                        int read = input.read(buffer, 0, wanted);
                        if (read < 0)
                            throw new IOException("Microsoft Graph attachment changed during upload");
                        out.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
                int status = connection.getResponseCode();
                if (status == 201)
                    return new GraphAttachmentUpload.PutResult(status, null);
                if (status == 200) {
                    GraphAttachmentUpload.Session state = parseUploadSession(
                            Helper.readStream(connection.getInputStream()), opaqueUploadUrl);
                    return new GraphAttachmentUpload.PutResult(status, state.nextExpectedRanges);
                }
                throw new GraphAttachmentUpload.HttpFailure(status,
                        connection.getHeaderField("Retry-After"));
            } finally {
                connection.disconnect();
            }
        }

        private String graphMessageUrl(String draftId) {
            return MicrosoftGraph.graphMessageUrl(draftId);
        }

        private void waitForRetry(String retryAfter) throws IOException {
            progress.setPhase(GraphSendAttempt.Phase.PREPARING_DRAFT);
            long delay = GraphAttachmentUpload.retryAfterMillis(retryAfter, System.currentTimeMillis());
            if (delay == 0)
                delay = 1000L;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Microsoft Graph", ex);
            }
        }
    }

    private static HttpsURLConnection openGraphConnection(Context context, URL url, String method, String accessToken,
            int readTimeout, GraphSendAttempt.Progress progress) throws IOException {
        if (progress.isCancelled())
            throw new IOException("Microsoft Graph send deadline exceeded");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        progress.setConnection(connection);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setReadTimeout(readTimeout);
        connection.setConnectTimeout(GraphTimeouts.CONNECT_MILLIS);
        connection.setUseCaches(false);
        ConnectionHelper.setUserAgent(context, connection);
        if (accessToken != null)
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        return connection;
    }

    private static HttpsURLConnection openUploadConnection(Context context, String opaqueUploadUrl, String method,
            int readTimeout, GraphSendAttempt.Progress progress) throws IOException {
        if (progress.isCancelled())
            throw new IOException("Microsoft Graph send deadline exceeded");
        final URL url;
        try {
            url = new URL(opaqueUploadUrl);
        } catch (Exception ex) {
            throw new IOException("Invalid Microsoft Graph upload session URL");
        }
        if (!"https".equalsIgnoreCase(url.getProtocol()))
            throw new IOException("Invalid Microsoft Graph upload session URL");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        progress.setConnection(connection);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setReadTimeout(readTimeout);
        connection.setConnectTimeout(GraphTimeouts.CONNECT_MILLIS);
        connection.setUseCaches(false);
        ConnectionHelper.setUserAgent(context, connection);
        // The opaque URL is preauthenticated. Never add Authorization or modify/log its value.
        return connection;
    }

    private static GraphAttachmentUpload.Session parseUploadSession(String response, String opaqueUrl)
            throws IOException {
        try {
            JSONObject json = new JSONObject(response);
            String uploadUrl = (opaqueUrl == null ? json.optString("uploadUrl", null) : opaqueUrl);
            JSONArray ranges = json.optJSONArray("nextExpectedRanges");
            List<String> expected = new ArrayList<>();
            if (ranges != null)
                for (int index = 0; index < ranges.length(); index++)
                    expected.add(ranges.getString(index));
            return new GraphAttachmentUpload.Session(uploadUrl, expected);
        } catch (org.json.JSONException ex) {
            throw new IOException("Invalid Microsoft Graph upload session response");
        }
    }

    private static void logSendStage(Context context, long operationId, long messageId,
                                     String stage, Integer status, long elapsed) {
        EntityLog.log(context, "Graph send stage=" + stage +
                " op=" + operationId + " message=" + messageId +
                (status == null ? "" : " status=" + status) +
                " elapsed=" + elapsed + "ms");
    }

    static int downloadContacts(Context context, long account, AuthState authState) throws IOException, JSONException, MessagingException {
        int count = 0;
        DB db = DB.getInstance(context);

        ServiceAuthenticator.OAuthRefresh(context,
                "outlookgraph", ServiceAuthenticator.AUTH_TYPE_GRAPH, "contacts", authState, BuildConfig.DEBUG);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString("graph.contacts." + account, authState.jsonSerializeString()).apply();

        // https://learn.microsoft.com/en-us/graph/api/user-list-contacts?view=graph-rest-1.0&tabs=http
        URL url = new URL(MicrosoftGraph.GRAPH_ENDPOINT + "contacts");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setReadTimeout(GRAPH_TIMEOUT * 1000);
        connection.setConnectTimeout(GRAPH_TIMEOUT * 1000);
        ConnectionHelper.setUserAgent(context, connection);
        connection.setRequestProperty("Authorization", "Bearer " + authState.getAccessToken());
        connection.connect();

        try {
            int status = connection.getResponseCode();
            if (status == HttpsURLConnection.HTTP_OK) {
                String response = Helper.readStream(connection.getInputStream());
                JSONObject jroot = new JSONObject(response);
                JSONArray jvalue = jroot.getJSONArray("value");
                for (int i = 0; i < jvalue.length(); i++) {
                    JSONObject jcontact = jvalue.getJSONObject(i);
                    String displayName = jcontact.optString("displayName");
                    if (TextUtils.isEmpty(displayName))
                        displayName = null;
                    if (jcontact.has("emailAddresses")) {
                        JSONArray jemailAddresses = jcontact.getJSONArray("emailAddresses");
                        for (int j = 0; j < jemailAddresses.length(); j++) {
                            JSONObject jemail = jemailAddresses.getJSONObject(j);
                            String email = jemail.optString("address");
                            if (!TextUtils.isEmpty(email)) {
                                EntityContact contact = db.contact().getContact(account, EntityContact.TYPE_TO, email);
                                EntityLog.log(context, "Graph/contacts " + displayName + " <" + email + ">" +
                                        " account=" + account + " exists=" + (contact != null));
                                if (contact == null) {
                                    contact = new EntityContact();
                                    contact.account = account;
                                    contact.type = EntityContact.TYPE_TO;
                                    contact.email = email;
                                    contact.name = displayName;
                                    contact.times_contacted = 0;
                                    contact.first_contacted = new Date().getTime();
                                    contact.last_contacted = contact.first_contacted;
                                    db.contact().insertContact(contact);
                                    count++;
                                }
                            }
                        }
                    }
                }
            } else {
                String error = "Error " + status + ": " + connection.getResponseMessage();
                try {
                    InputStream is = connection.getErrorStream();
                    if (is != null)
                        error += "\n" + Helper.readStream(is);
                } catch (Throwable ex) {
                    Log.w(ex);
                }
                throw new IOException(error);
            }
        } finally {
            connection.disconnect();
        }

        return count;
    }
}
