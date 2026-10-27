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

import android.util.Base64;
import android.util.Base64OutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.mail.Session;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.net.ssl.HttpsURLConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Minimal Gmail REST transport for messages composed by FairEmail.
 * Gmail API JSON and message identifiers are deliberately not mapped to an IMAP model.
 */
final class GmailApi {
    static final String PROVIDER_ID = "gmailapi";
    static final String SEND_ENDPOINT =
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    static final String MESSAGES_ENDPOINT =
            "https://gmail.googleapis.com/gmail/v1/users/me/messages";
    static final String LABELS_ENDPOINT =
            "https://gmail.googleapis.com/gmail/v1/users/me/labels";
    static final String HISTORY_ENDPOINT =
            "https://gmail.googleapis.com/gmail/v1/users/me/history";
    static final String PROFILE_ENDPOINT =
            "https://gmail.googleapis.com/gmail/v1/users/me/profile";
    static final int TIMEOUT_SECONDS = 20;
    static final int SEND_TIMEOUT_SECONDS = 90;
    private static final int CHUNK_SIZE = 8192;
    static final int MAX_RAW_MIME_BYTES = 50 * 1024 * 1024;
    private static final int MAX_RAW_RESPONSE_BYTES = 70 * 1024 * 1024;
    static final int MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024;
    static final int MAX_ATTACHMENT_RESPONSE_BYTES =
            (int) (((MAX_ATTACHMENT_BYTES + 2L) / 3L) * 4L + 4096L);
    private static final byte[] JSON_PREFIX = "{\"raw\":\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_SUFFIX = "\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_THREAD_ID_PREFIX = "\",\"threadId\":".getBytes(StandardCharsets.UTF_8);

    interface ConnectionFactory {
        HttpsURLConnection open(URL url) throws IOException;
    }

    interface Base64UrlEncoder {
        OutputStream wrap(OutputStream output) throws IOException;
    }

    interface Base64UrlDecoder {
        byte[] decode(String value) throws IllegalArgumentException;
    }

    interface TokenRefresher {
        String refresh(String rejectedToken) throws IOException;
    }

    interface QuotaUsageListener {
        void onRequest(int quotaUnits);
    }

    private String accessToken;
    private TokenRefresher tokenRefresher;
    private QuotaUsageListener quotaUsageListener;
    private final ConnectionFactory connectionFactory;
    private final Base64UrlEncoder encoder;
    private final Base64UrlDecoder decoder;

    GmailApi(String accessToken) {
        this(accessToken, null,
                new ConnectionFactory() {
                    @Override
                    public HttpsURLConnection open(URL url) throws IOException {
                        return (HttpsURLConnection) url.openConnection();
                    }
                },
                new Base64UrlEncoder() {
                    @Override
                    public OutputStream wrap(OutputStream output) {
                        return new Base64OutputStream(output,
                                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    }
                },
                new Base64UrlDecoder() {
                    @Override
                    public byte[] decode(String value) {
                        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP);
                    }
                });
    }

    // Package-private seams keep request construction testable without a live Google account.
    GmailApi(String accessToken, ConnectionFactory connectionFactory, Base64UrlEncoder encoder) {
        this(accessToken, null, connectionFactory, encoder, new Base64UrlDecoder() {
            @Override
            public byte[] decode(String value) {
                return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP);
            }
        });
    }

    GmailApi(String accessToken, ConnectionFactory connectionFactory, Base64UrlEncoder encoder,
             Base64UrlDecoder decoder) {
        this(accessToken, null, connectionFactory, encoder, decoder);
    }

    GmailApi(String accessToken, TokenRefresher tokenRefresher,
             ConnectionFactory connectionFactory, Base64UrlEncoder encoder,
             Base64UrlDecoder decoder) {
        if (accessToken == null || accessToken.trim().isEmpty())
            throw new IllegalArgumentException("Missing Gmail access token");
        if (connectionFactory == null || encoder == null || decoder == null)
            throw new IllegalArgumentException("Missing Gmail REST transport dependency");

        this.accessToken = accessToken;
        this.tokenRefresher = tokenRefresher;
        this.connectionFactory = connectionFactory;
        this.encoder = encoder;
        this.decoder = decoder;
    }

    void setTokenRefresher(TokenRefresher tokenRefresher) {
        this.tokenRefresher = tokenRefresher;
    }

    void setQuotaUsageListener(QuotaUsageListener quotaUsageListener) {
        this.quotaUsageListener = quotaUsageListener;
    }

    /**
     * Sends the already-composed MIME message through users.messages.send.
     * The only wire transformation is Gmail's required unpadded base64url encoding
     * inside the native JSON {@code raw} field. The returned JSON is left untouched.
     */
    String send(MimeMessage message) throws IOException, MessagingException {
        return send(message, null);
    }

    String send(MimeMessage message, String gmailThreadId) throws IOException, MessagingException {
        if (message == null)
            throw new IllegalArgumentException("Missing MIME message");
        if (gmailThreadId != null && gmailThreadId.trim().isEmpty())
            gmailThreadId = null;

        for (int attempt = 0; attempt < 2; attempt++)
            try {
                return sendOnce(message, gmailThreadId);
            } catch (ApiException ex) {
                if (attempt != 0 || ex.status != 401)
                    throw ex;
                refreshRejectedToken(ex);
            }
        throw new IOException("Gmail API send exhausted its bounded authorization retry");
    }

    private String sendOnce(MimeMessage message, String gmailThreadId) throws IOException, MessagingException {
        HttpsURLConnection connection = null;
        boolean requestBodyStarted = false;
        try {
            recordQuotaUsage("POST", SEND_ENDPOINT);
            connection = connectionFactory.open(new URL(SEND_ENDPOINT));
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(SEND_TIMEOUT_SECONDS * 1000);
            connection.setReadTimeout(SEND_TIMEOUT_SECONDS * 1000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(CHUNK_SIZE);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.connect();

            try (OutputStream output = connection.getOutputStream()) {
                requestBodyStarted = true;
                output.write(JSON_PREFIX);
                try (OutputStream encoded = encoder.wrap(new NonClosingOutputStream(output))) {
                    message.writeTo(encoded);
                }
                if (gmailThreadId == null)
                    output.write(JSON_SUFFIX);
                else {
                    output.write(JSON_THREAD_ID_PREFIX);
                    output.write(JSONObject.quote(gmailThreadId).getBytes(StandardCharsets.UTF_8));
                    output.write('}');
                }
            }

            int status = connection.getResponseCode();
            if (status >= 500 && status < 600)
                throw new SendOutcomeUnknownException(
                        createApiException(connection, "send", status), status);
            if (status < 200 || status >= 300)
                throw createApiException(connection, "send", status);

            try (InputStream input = connection.getInputStream()) {
                return readResponse(input);
            } catch (IOException responseReadError) {
                // HTTP 200 confirms acceptance; an unread body must not cause a duplicate retry.
                return "";
            }
        } catch (IOException ex) {
            if (requestBodyStarted && !(ex instanceof ApiException) &&
                    !(ex instanceof SendOutcomeUnknownException))
                throw new SendOutcomeUnknownException(ex);
            throw ex;
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    /**
     * Retrieves the account's own Gmail address using the already granted read scope.
     */
    Profile getProfile() throws IOException, JSONException {
        JSONObject json = new JSONObject(get(PROFILE_ENDPOINT, "profile"));
        String emailAddress = json.optString("emailAddress", null);
        if (emailAddress == null || emailAddress.trim().isEmpty())
            throw new IOException("Gmail profile response did not contain an email address");
        return new Profile(emailAddress, json.optString("historyId", null),
                json.optLong("messagesTotal", -1), json.optLong("threadsTotal", -1));
    }

    /** Lists system and user labels using the existing gmail.readonly grant. */
    List<Label> listLabels() throws IOException, JSONException {
        JSONObject json = new JSONObject(get(LABELS_ENDPOINT, "labels list"));
        List<Label> labels = new ArrayList<>();
        JSONArray entries = json.optJSONArray("labels");
        if (entries != null)
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String id = entry.optString("id", null);
                if (id == null || id.isEmpty())
                    throw new JSONException("Gmail labels response contained a label without an id");
                labels.add(new Label(id, entry.optString("name", id),
                        entry.optString("type", null)));
            }
        return Collections.unmodifiableList(labels);
    }

    /**
     * Lists Gmail-native message references. Opaque Gmail IDs and nextPageToken
     * values are preserved as Gmail API values; neither is an IMAP UID.
     */
    MessagePage listMessages(String query, String pageToken, int maxResults)
            throws IOException, JSONException {
        return listMessages(query, pageToken, maxResults, false);
    }

    MessagePage listMessages(String query, String pageToken, int maxResults, boolean includeSpamTrash)
            throws IOException, JSONException {
        return listMessagesForFolder(null, query, pageToken, maxResults, includeSpamTrash);
    }

    MessagePage listMessagesForFolder(String labelId, String query, String pageToken,
                                      int maxResults, boolean includeSpamTrash)
            throws IOException, JSONException {
        if (maxResults < 1 || maxResults > 500)
            throw new IllegalArgumentException("Gmail maxResults must be between 1 and 500");

        StringBuilder endpoint = new StringBuilder(MESSAGES_ENDPOINT)
                .append("?maxResults=").append(maxResults);
        if (labelId != null && !labelId.trim().isEmpty())
            endpoint.append("&labelIds=").append(encodeQuery(labelId));
        if (query != null && !query.trim().isEmpty())
            endpoint.append("&q=").append(encodeQuery(query));
        if (includeSpamTrash)
            endpoint.append("&includeSpamTrash=true");
        if (pageToken != null && !pageToken.isEmpty())
            endpoint.append("&pageToken=").append(encodeQuery(pageToken));

        final JSONObject json;
        try {
            json = new JSONObject(get(endpoint.toString(), "list"));
        } catch (ApiException ex) {
            if (pageToken != null && !pageToken.isEmpty() && ex.status == 400)
                throw new InvalidPageTokenException(ex);
            throw ex;
        }
        List<MessageReference> messages = new ArrayList<>();
        JSONArray entries = json.optJSONArray("messages");
        if (entries != null)
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String id = entry.optString("id", null);
                if (id == null || id.isEmpty())
                    throw new JSONException("Gmail list response contained a message without an id");
                messages.add(new MessageReference(id, entry.optString("threadId", null)));
            }

        String nextPageToken = json.optString("nextPageToken", null);
        if (nextPageToken != null && nextPageToken.isEmpty())
            nextPageToken = null;
        return new MessagePage(messages, nextPageToken, json.optLong("resultSizeEstimate", -1));
    }

    /** Lists one bounded page of changes since a committed mailbox history ID. */
    HistoryPage listHistory(String startHistoryId, String pageToken, int maxResults)
            throws IOException, JSONException {
        if (startHistoryId == null || startHistoryId.trim().isEmpty())
            throw new IllegalArgumentException("Missing Gmail start history ID");
        if (maxResults < 1 || maxResults > 500)
            throw new IllegalArgumentException("Gmail history maxResults must be between 1 and 500");

        StringBuilder endpoint = new StringBuilder(HISTORY_ENDPOINT)
                .append("?startHistoryId=").append(encodeQuery(startHistoryId))
                .append("&maxResults=").append(maxResults);
        if (pageToken != null && !pageToken.isEmpty())
            endpoint.append("&pageToken=").append(encodeQuery(pageToken));

        final JSONObject json;
        try {
            json = new JSONObject(get(endpoint.toString(), "history list"));
        } catch (ApiException ex) {
            if (ex.status == 404)
                throw new HistoryExpiredException(ex);
            throw ex;
        }

        Map<String, MessageChange> changes = new LinkedHashMap<>();
        JSONArray entries = json.optJSONArray("history");
        if (entries != null)
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                addHistoryChanges(changes, entry.optJSONArray("messagesAdded"), false);
                addHistoryChanges(changes, entry.optJSONArray("labelsAdded"), false);
                addHistoryChanges(changes, entry.optJSONArray("labelsRemoved"), false);
                addHistoryChanges(changes, entry.optJSONArray("messagesDeleted"), true);
            }

        String nextPageToken = json.optString("nextPageToken", null);
        if (nextPageToken != null && nextPageToken.isEmpty())
            nextPageToken = null;
        return new HistoryPage(new ArrayList<>(changes.values()), nextPageToken,
                json.optString("historyId", null));
    }

    /** Reads only labelIds for a cached message; this does not request mutation scopes. */
    List<String> getMessageLabels(String gmailMessageId) throws IOException, JSONException {
        if (gmailMessageId == null || gmailMessageId.trim().isEmpty())
            throw new IllegalArgumentException("Missing Gmail message id");
        String endpoint = MESSAGES_ENDPOINT + "/" + encodePathSegment(gmailMessageId) + "?format=minimal";
        try {
            List<String> labels = Collections.unmodifiableList(
                    parseLabelIds(new JSONObject(get(endpoint, "get labels"))));
            GmailMutationLog.http("get_labels", HttpsURLConnection.HTTP_OK, "success");
            return labels;
        } catch (ApiException ex) {
            GmailMutationLog.http("get_labels", ex.status, "failure");
            if (ex.status == 404)
                throw new MessageUnavailableException(ex);
            throw ex;
        }
    }

    /** Apply a Gmail label membership delta without disturbing other labels. */
    List<String> modifyMessage(String gmailMessageId, List<String> addLabelIds,
                               List<String> removeLabelIds) throws IOException, JSONException {
        if (gmailMessageId == null || gmailMessageId.trim().isEmpty())
            throw new IllegalArgumentException("Missing Gmail message id");
        JSONObject body = new JSONObject();
        if (addLabelIds != null && !addLabelIds.isEmpty())
            body.put("addLabelIds", new JSONArray(addLabelIds));
        if (removeLabelIds != null && !removeLabelIds.isEmpty())
            body.put("removeLabelIds", new JSONArray(removeLabelIds));
        if (body.length() == 0)
            return getMessageLabels(gmailMessageId);
        String endpoint = MESSAGES_ENDPOINT + "/" + encodePathSegment(gmailMessageId) + "/modify";
        return mutateLabels(endpoint, "modify", body, gmailMessageId);
    }

    /** Move a Gmail message into Trash using Gmail's native trash action. */
    List<String> trashMessage(String gmailMessageId) throws IOException, JSONException {
        return mutateMessageAction(gmailMessageId, "trash");
    }

    /** Restore a Gmail message from Trash using Gmail's native untrash action. */
    List<String> untrashMessage(String gmailMessageId) throws IOException, JSONException {
        return mutateMessageAction(gmailMessageId, "untrash");
    }

    /** Permanently delete a Gmail message after the caller prepares and verifies its Trash labels. */
    void deleteMessage(String gmailMessageId) throws IOException {
        if (gmailMessageId == null || gmailMessageId.trim().isEmpty())
            throw new IllegalArgumentException("Missing Gmail message id");
        String endpoint = MESSAGES_ENDPOINT + "/" + encodePathSegment(gmailMessageId);
        for (int attempt = 0; attempt < 2; attempt++)
            try {
                deleteMessageOnce(endpoint);
                return;
            } catch (MessageUnavailableException ex) {
                throw ex;
            } catch (ApiException ex) {
                if (attempt == 0 && ex.status == 401) {
                    refreshRejectedToken(ex);
                    continue;
                }
                if (ex.status == 408 || ex.status >= 500)
                    throw new DeleteOutcomeUnknownException(ex);
                throw ex;
            } catch (IOException ex) {
                // Once connected, a lost response cannot establish whether Gmail applied DELETE.
                throw new DeleteOutcomeUnknownException(ex);
            }
        throw new IOException("Gmail API permanent delete exhausted its bounded authorization retry");
    }

    private List<String> mutateMessageAction(String gmailMessageId, String action)
            throws IOException, JSONException {
        if (gmailMessageId == null || gmailMessageId.trim().isEmpty())
            throw new IllegalArgumentException("Missing Gmail message id");
        String endpoint = MESSAGES_ENDPOINT + "/" + encodePathSegment(gmailMessageId) + "/" + action;
        return mutateLabels(endpoint, action, new JSONObject(), gmailMessageId);
    }

    private List<String> mutateLabels(String endpoint, String operation, JSONObject body,
                                      String gmailMessageId) throws IOException, JSONException {
        for (int attempt = 0; attempt < 2; attempt++)
            try {
                String response = mutateOnce(endpoint, operation, body);
                JSONObject json = new JSONObject(response);
                String id = json.optString("id", null);
                if (!gmailMessageId.equals(id))
                    throw new IOException("Gmail " + operation + " response returned an inconsistent message id");
                return Collections.unmodifiableList(parseLabelIds(json));
            } catch (ApiException ex) {
                if (ex.status == 404)
                    throw new MessageUnavailableException(ex);
                if (attempt != 0 || ex.status != 401)
                    throw ex;
                refreshRejectedToken(ex);
            }
        throw new IOException("Gmail API " + operation + " exhausted its bounded authorization retry");
    }

    private String mutateOnce(String endpoint, String operation, JSONObject body) throws IOException {
        HttpsURLConnection connection = null;
        try {
            recordQuotaUsage("POST", endpoint);
            connection = connectionFactory.open(new URL(endpoint));
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_SECONDS * 1000);
            connection.setReadTimeout(TIMEOUT_SECONDS * 1000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.connect();
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status != HttpsURLConnection.HTTP_OK) {
                GmailMutationLog.http(operation, status, "failure");
                throw createApiException(connection, operation, status);
            }
            try (InputStream input = connection.getInputStream()) {
                String response = readResponse(input);
                GmailMutationLog.http(operation, status, "success");
                return response;
            } catch (IOException ex) {
                GmailMutationLog.http(operation, status, "response_read_failed");
                throw ex;
            }
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    private void deleteMessageOnce(String endpoint) throws IOException {
        HttpsURLConnection connection = null;
        try {
            recordQuotaUsage("DELETE", endpoint);
            connection = connectionFactory.open(new URL(endpoint));
            connection.setRequestMethod("DELETE");
            connection.setConnectTimeout(TIMEOUT_SECONDS * 1000);
            connection.setReadTimeout(TIMEOUT_SECONDS * 1000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");
            connection.connect();
            int status = connection.getResponseCode();
            if (status != HttpsURLConnection.HTTP_OK && status != HttpsURLConnection.HTTP_NO_CONTENT) {
                GmailMutationLog.http("delete", status, "failure");
                ApiException error = createApiException(connection, "delete", status);
                if (status == 404)
                    throw new MessageUnavailableException(error);
                throw error;
            }
            GmailMutationLog.http("delete", status, "success");
            try (InputStream input = connection.getInputStream()) {
                if (input != null)
                    readResponse(input);
            } catch (IOException ignored) {
                // A 204 response has no body.
            }
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    /** Reads only stable message metadata needed by the one-time received-date repair. */
    MessageMetadata getMessageMetadata(String gmailMessageId) throws IOException, JSONException {
        if (gmailMessageId == null || gmailMessageId.trim().isEmpty())
            throw new IllegalArgumentException("Missing Gmail message id");
        // Full exposes the top-level internalDate; the field mask omits payload/body and attachments.
        String endpoint = MESSAGES_ENDPOINT + "/" + encodePathSegment(gmailMessageId) +
                "?format=full&fields=id%2CthreadId%2CinternalDate%2ClabelIds";
        final JSONObject json;
        try {
            json = new JSONObject(get(endpoint, "get metadata"));
        } catch (ApiException ex) {
            if (ex.status == 404)
                throw new MessageUnavailableException(ex);
            throw ex;
        }
        String id = json.optString("id", null);
        if (id == null || id.isEmpty())
            throw new IOException("Gmail metadata response did not contain a message id");
        if (!gmailMessageId.equals(id))
            throw new IOException("Gmail metadata response returned an inconsistent message id");
        long internalDate = json.optLong("internalDate", -1);
        if (internalDate < 0)
            throw new IOException("Gmail metadata response did not contain a valid internalDate");
        return new MessageMetadata(id, json.optString("threadId", null), internalDate,
                parseLabelIds(json));
    }

    /**
     * Retrieves Gmail's RFC822 representation, decodes the base64url raw field
     * once, then parses those bytes directly as a JavaMail MIME message.
     */
    ReceivedMessage getMessage(String gmailMessageId)
            throws IOException, JSONException, MessagingException {
        return getMessage(gmailMessageId, true);
    }

    ReceivedMessage getMessage(String gmailMessageId, boolean includeBody)
            throws IOException, JSONException, MessagingException {
        if (gmailMessageId == null || gmailMessageId.trim().isEmpty())
            throw new IllegalArgumentException("Missing Gmail message id");

        String endpoint = MESSAGES_ENDPOINT + "/" + encodePathSegment(gmailMessageId) +
                "?format=full&fields=" + encodeQuery(getMessageFields(includeBody));
        final JSONObject json;
        try {
            json = new JSONObject(get(endpoint, "get"));
        } catch (ApiException ex) {
            if (ex.status == 404)
                throw new MessageUnavailableException(ex);
            throw ex;
        }
        Session session = Session.getInstance(MessageHelper.getSessionProperties(true), null);
        GmailApiMessageParser.Result parsed = GmailApiMessageParser.parseDetailed(
                session, json, includeBody, decoder, new GmailApiMessageParser.AttachmentDataFetcher() {
                    @Override
                    public byte[] fetch(String attachmentId) throws IOException, JSONException {
                        return getAttachmentBytes(gmailMessageId, attachmentId);
                    }
                });
        MimeMessage mimeMessage = parsed.mimeMessage;
        List<String> labels = parseLabelIds(json);
        String id = json.optString("id", gmailMessageId);
        if (!gmailMessageId.equals(id))
            throw new IOException("Gmail message response returned an inconsistent message id");
        return new ReceivedMessage(
                id,
                json.optString("threadId", null),
                json.optString("historyId", null),
                json.optLong("internalDate", -1),
                json.optLong("sizeEstimate", -1),
                json.optString("snippet", null), parsed.bodyComplete, labels, null, mimeMessage,
                parsed.bodyParts, parsed.incompleteBodyParts, parsed.fetchedBodyParts,
                parsed.attachmentParts, parsed.attachmentIdParts, parsed.inlineDataAttachmentParts);
    }

    byte[] getRawMessage(String gmailMessageId) throws IOException, JSONException {
        if (gmailMessageId == null || gmailMessageId.trim().isEmpty())
            throw new IllegalArgumentException("Missing Gmail message id");
        String endpoint = MESSAGES_ENDPOINT + "/" + encodePathSegment(gmailMessageId) +
                "?format=raw&fields=" + encodeQuery("id,raw");
        JSONObject json = new JSONObject(get(endpoint, "get raw message", MAX_RAW_RESPONSE_BYTES));
        String id = json.optString("id", null);
        if (id == null || !gmailMessageId.equals(id))
            throw new IOException("Gmail raw message response returned an inconsistent message id");
        String encoded = json.optString("raw", null);
        if (encoded == null || encoded.isEmpty())
            throw new IOException("Gmail raw message response did not contain complete MIME data");
        try {
            byte[] bytes = decoder.decode(encoded);
            if (bytes.length == 0)
                throw new IOException("Gmail raw message response contained empty MIME data");
            if (bytes.length > MAX_RAW_MIME_BYTES)
                throw new IOException("Gmail raw message exceeds the local size limit");
            return bytes;
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid Gmail base64url raw message data", ex);
        }
    }

    byte[] getAttachmentBytes(String gmailMessageId, String attachmentId)
            throws IOException, JSONException {
        if (gmailMessageId == null || gmailMessageId.trim().isEmpty() ||
                attachmentId == null || attachmentId.trim().isEmpty())
            throw new IllegalArgumentException("Missing Gmail message or attachment ID");
        String endpoint = MESSAGES_ENDPOINT + "/" + encodePathSegment(gmailMessageId) +
                "/attachments/" + encodePathSegment(attachmentId);
        JSONObject json = new JSONObject(get(endpoint, "get attachment", MAX_ATTACHMENT_RESPONSE_BYTES));
        String encoded = json.optString("data", null);
        if (encoded == null)
            throw new IOException("Gmail attachment response did not contain base64url data");
        return decodeAttachmentData(encoded, MAX_ATTACHMENT_BYTES, decoder);
    }

    static byte[] decodeAttachmentData(String encoded, int maxDecodedBytes,
                                       Base64UrlDecoder decoder) throws IOException {
        if (encoded == null || decoder == null || maxDecodedBytes < 0)
            throw new IllegalArgumentException("Invalid Gmail attachment decoder input");
        long maxEncodedChars = ((maxDecodedBytes + 2L) / 3L) * 4L;
        if (encoded.length() > maxEncodedChars)
            throw new IOException("Gmail attachment exceeds the local size limit");
        try {
            byte[] bytes = decoder.decode(encoded);
            if (bytes.length > maxDecodedBytes)
                throw new IOException("Gmail attachment exceeds the local size limit");
            return bytes;
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid Gmail base64url attachment data", ex);
        }
    }

    private static String getMessageFields(boolean includeBody) {
        String bodyFields = (includeBody ? "size,attachmentId,data" : "size,attachmentId");
        String partFields = "partId,mimeType,filename,headers(name,value),body(" + bodyFields + ")";
        // MIME nesting is normally shallow. Include a bounded deep projection without ever
        // selecting body.data when the body is being deferred.
        for (int i = 0; i < 16; i++)
            partFields = "partId,mimeType,filename,headers(name,value),body(" + bodyFields + "),parts(" +
                    partFields + ")";
        return "id,threadId,historyId,internalDate,sizeEstimate,snippet,labelIds,payload(" +
                partFields + ")";
    }

    private String get(String endpoint, String operation) throws IOException {
        return get(endpoint, operation, Integer.MAX_VALUE);
    }

    private String get(String endpoint, String operation, int maxResponseBytes) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++)
            try {
                return getOnce(endpoint, operation, maxResponseBytes);
            } catch (ApiException ex) {
                if (attempt != 0 || ex.status != 401)
                    throw ex;
                refreshRejectedToken(ex);
            }
        throw new IOException("Gmail API request exhausted its bounded authorization retry");
    }

    private String getOnce(String endpoint, String operation, int maxResponseBytes) throws IOException {
        HttpsURLConnection connection = null;
        try {
            recordQuotaUsage("GET", endpoint);
            connection = connectionFactory.open(new URL(endpoint));
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_SECONDS * 1000);
            connection.setReadTimeout(TIMEOUT_SECONDS * 1000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");
            connection.connect();

            int status = connection.getResponseCode();
            if (status != HttpsURLConnection.HTTP_OK)
                throw createApiException(connection, operation, status);

            try (InputStream input = connection.getInputStream()) {
                return readResponse(input, maxResponseBytes);
            }
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    static int quotaUnitsForRequest(String method, String endpoint) {
        if (endpoint == null)
            return 0;
        final String path;
        try {
            path = new URL(endpoint).getPath();
        } catch (Exception ex) {
            return 0;
        }
        if ("POST".equals(method) && path.endsWith("/messages/send"))
            return GmailRateLimitBackoff.MESSAGES_SEND_QUOTA_UNITS;
        if ("POST".equals(method) && (path.endsWith("/modify") ||
                path.endsWith("/trash") || path.endsWith("/untrash")))
            return GmailRateLimitBackoff.MESSAGES_MODIFY_QUOTA_UNITS;
        if ("DELETE".equals(method) && path.contains("/messages/"))
            return GmailRateLimitBackoff.MESSAGES_DELETE_QUOTA_UNITS;
        if (!"GET".equals(method))
            return 0;
        if (path.endsWith("/labels"))
            return GmailRateLimitBackoff.LABELS_LIST_QUOTA_UNITS;
        if (path.endsWith("/profile"))
            return GmailRateLimitBackoff.PROFILE_GET_QUOTA_UNITS;
        if (path.endsWith("/history"))
            return GmailRateLimitBackoff.HISTORY_LIST_QUOTA_UNITS;
        if (path.endsWith("/messages"))
            return GmailRateLimitBackoff.MESSAGES_LIST_QUOTA_UNITS;
        if (path.contains("/messages/"))
            return GmailRateLimitBackoff.MESSAGES_GET_QUOTA_UNITS;
        return 0;
    }

    private void recordQuotaUsage(String method, String endpoint) {
        if (quotaUsageListener != null) {
            int units = quotaUnitsForRequest(method, endpoint);
            if (units > 0)
                quotaUsageListener.onRequest(units);
        }
    }

    private void refreshRejectedToken(ApiException unauthorized) throws IOException {
        if (tokenRefresher == null)
            throw unauthorized;
        String replacement = tokenRefresher.refresh(accessToken);
        if (replacement == null || replacement.trim().isEmpty() || replacement.equals(accessToken))
            throw new ApiException(401,
                    "Gmail requires Google authorization: token reacquisition did not replace the rejected token",
                    -1, false);
        accessToken = replacement;
    }

    private static ApiException createApiException(HttpsURLConnection connection,
                                                    String operation, int status)
            throws IOException {
        StringBuilder error = new StringBuilder("Gmail API ").append(operation)
                .append(" failed: HTTP ").append(status);
        String responseMessage = connection.getResponseMessage();
        if (responseMessage != null)
            error.append(' ').append(responseMessage);
        String body = "";
        try (InputStream input = connection.getErrorStream()) {
            if (input != null)
                body = readResponse(input);
        }
        if (!body.isEmpty())
            error.append('\n').append(body);
        long retryAfter = parseRetryAfter(connection.getHeaderField("Retry-After"),
                System.currentTimeMillis());
        return new ApiException(status, error.toString(), retryAfter,
                isRateLimitFailure(status, body));
    }

    private static boolean isRateLimitFailure(int status, String body) {
        if (status == 429)
            return true;
        if (status != 403 || body == null)
            return false;
        String normalized = body.toLowerCase(java.util.Locale.ROOT)
                .replace(" ", "").replace("_", "");
        return (normalized.contains("ratelimitexceeded") ||
                normalized.contains("userratelimitexceeded") ||
                normalized.contains("quota.metric") ||
                normalized.contains("totalquerycost") ||
                normalized.contains("quotaunitsperminute"));
    }

    static long parseRetryAfter(String value, long nowMs) {
        if (value == null || value.trim().isEmpty())
            return -1;
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds <= 0)
                return 0;
            return (seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L);
        } catch (NumberFormatException ignored) {
            // Retry-After may also be an HTTP date.
        }
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            java.util.Date date = format.parse(trimmed);
            return (date == null ? -1 : Math.max(0, date.getTime() - nowMs));
        } catch (java.text.ParseException ex) {
            return -1;
        }
    }

    private static String encodeQuery(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String encodePathSegment(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static List<String> parseLabelIds(JSONObject json) throws JSONException {
        List<String> labels = new ArrayList<>();
        JSONArray labelIds = json.optJSONArray("labelIds");
        if (labelIds != null)
            for (int i = 0; i < labelIds.length(); i++)
                labels.add(labelIds.getString(i));
        return labels;
    }

    private static void addHistoryChanges(Map<String, MessageChange> changes,
                                          JSONArray entries, boolean deleted)
            throws JSONException {
        if (entries == null)
            return;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            JSONObject message = entry.optJSONObject("message");
            if (message == null)
                throw new JSONException("Gmail history change did not contain a message");
            String id = message.optString("id", null);
            if (id == null || id.isEmpty())
                throw new JSONException("Gmail history message did not contain an id");
            String threadId = message.optString("threadId", null);
            MessageChange previous = changes.get(id);
            if ((threadId == null || threadId.isEmpty()) && previous != null)
                threadId = previous.threadId;
            boolean labelsPresent = message.has("labelIds") && !message.isNull("labelIds");
            List<String> labels = (labelsPresent ? parseLabelIds(message)
                    : (previous == null ? Collections.<String>emptyList() : previous.labels));
            labelsPresent = labelsPresent || (previous != null && previous.labelsPresent);
            changes.put(id, new MessageChange(id, threadId, deleted, labelsPresent, labels));
        }
    }

    static final class Profile {
        final String emailAddress;
        final String historyId;
        final long messagesTotal;
        final long threadsTotal;

        Profile(String emailAddress, String historyId, long messagesTotal, long threadsTotal) {
            this.emailAddress = emailAddress;
            this.historyId = historyId;
            this.messagesTotal = messagesTotal;
            this.threadsTotal = threadsTotal;
        }
    }

    static final class MessageReference {
        final String id;
        final String threadId;

        MessageReference(String id, String threadId) {
            this.id = id;
            this.threadId = threadId;
        }
    }

    static final class Label {
        final String id;
        final String name;
        final String type;

        Label(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }
    }

    static final class MessagePage {
        final List<MessageReference> messages;
        final String nextPageToken;
        final long resultSizeEstimate;

        MessagePage(List<MessageReference> messages, String nextPageToken, long resultSizeEstimate) {
            this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
            this.nextPageToken = nextPageToken;
            this.resultSizeEstimate = resultSizeEstimate;
        }
    }

    static final class MessageChange {
        final String id;
        final String threadId;
        final boolean deleted;
        final boolean labelsPresent;
        final List<String> labels;

        MessageChange(String id, String threadId, boolean deleted,
                      boolean labelsPresent, List<String> labels) {
            this.id = id;
            this.threadId = threadId;
            this.deleted = deleted;
            this.labelsPresent = labelsPresent;
            this.labels = Collections.unmodifiableList(new ArrayList<>(labels));
        }
    }

    static final class HistoryPage {
        final List<MessageChange> changes;
        final String nextPageToken;
        final String historyId;

        HistoryPage(List<MessageChange> changes, String nextPageToken, String historyId) {
            this.changes = Collections.unmodifiableList(new ArrayList<>(changes));
            this.nextPageToken = nextPageToken;
            this.historyId = historyId;
        }

        String toPendingJson() throws JSONException {
            JSONObject root = new JSONObject();
            if (nextPageToken != null)
                root.put("nextPageToken", nextPageToken);
            if (historyId != null)
                root.put("historyId", historyId);
            JSONArray entries = new JSONArray();
            for (MessageChange change : changes) {
                JSONObject entry = new JSONObject();
                entry.put("id", change.id);
                if (change.threadId != null)
                    entry.put("threadId", change.threadId);
                entry.put("deleted", change.deleted);
                entry.put("labelsPresent", change.labelsPresent);
                if (change.labelsPresent) {
                    JSONArray labels = new JSONArray();
                    for (String label : change.labels)
                        labels.put(label);
                    entry.put("labels", labels);
                }
                entries.put(entry);
            }
            root.put("changes", entries);
            return root.toString();
        }

        static HistoryPage fromPendingJson(String value) throws JSONException {
            JSONObject root = new JSONObject(value);
            JSONArray entries = root.optJSONArray("changes");
            List<MessageChange> changes = new ArrayList<>();
            if (entries != null)
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    JSONArray labelArray = entry.optJSONArray("labels");
                    List<String> labels = new ArrayList<>();
                    if (labelArray != null)
                        for (int j = 0; j < labelArray.length(); j++)
                            labels.add(labelArray.getString(j));
                    changes.add(new MessageChange(entry.getString("id"),
                            entry.optString("threadId", null), entry.optBoolean("deleted"),
                            entry.optBoolean("labelsPresent"), labels));
                }
            return new HistoryPage(changes, root.optString("nextPageToken", null),
                    root.optString("historyId", null));
        }
    }

    static class ApiException extends IOException {
        final int status;
        final long retryAfterMs;
        private final boolean rateLimitFailure;

        ApiException(int status, String message) {
            this(status, message, -1, false);
        }

        ApiException(int status, String message, long retryAfterMs, boolean rateLimitFailure) {
            super(message);
            this.status = status;
            this.retryAfterMs = retryAfterMs;
            this.rateLimitFailure = rateLimitFailure;
        }

        boolean isRateLimitFailure() {
            return rateLimitFailure;
        }
    }

    static final class SendOutcomeUnknownException extends IOException {
        final int status;

        SendOutcomeUnknownException(IOException cause) {
            this(cause, -1);
        }

        SendOutcomeUnknownException(IOException cause, int status) {
            super(status < 0
                            ? "Gmail send outcome unknown after request body transmission began; no HTTP response was received"
                            : "Gmail send outcome unknown after request body transmission; HTTP " + status +
                                    " does not confirm message acceptance",
                    cause);
            this.status = status;
        }
    }

    static final class DeleteOutcomeUnknownException extends IOException {
        DeleteOutcomeUnknownException(IOException cause) {
            super("Gmail permanent delete outcome is unknown; do not replay without reconciliation", cause);
        }
    }

    static final class HistoryExpiredException extends IOException {
        HistoryExpiredException(ApiException cause) {
            super(cause.getMessage(), cause);
        }
    }

    static final class InvalidPageTokenException extends IOException {
        InvalidPageTokenException(ApiException cause) {
            super(cause.getMessage(), cause);
        }
    }

    static final class MessageUnavailableException extends IOException {
        MessageUnavailableException(ApiException cause) {
            super(cause.getMessage(), cause);
        }
    }

    static final class ReceivedMessage {
        final String id;
        final String threadId;
        final String historyId;
        final long internalDate;
        final long sizeEstimate;
        final String snippet;
        final boolean bodyIncluded;
        final int bodyParts;
        final int incompleteBodyParts;
        final int fetchedBodyParts;
        final int attachmentParts;
        final int attachmentIdParts;
        final int inlineDataAttachmentParts;
        final List<String> labels;
        final byte[] rawRfc822;
        final MimeMessage mimeMessage;

        ReceivedMessage(String id, String threadId, String historyId, long internalDate,
                        long sizeEstimate, List<String> labels, byte[] rawRfc822,
                        MimeMessage mimeMessage) {
            this(id, threadId, historyId, internalDate, sizeEstimate,
                    null, true, labels, rawRfc822, mimeMessage);
        }

        ReceivedMessage(String id, String threadId, String historyId, long internalDate,
                        long sizeEstimate, String snippet, boolean bodyIncluded,
                        List<String> labels, byte[] rawRfc822,
                        MimeMessage mimeMessage) {
            this(id, threadId, historyId, internalDate, sizeEstimate, snippet, bodyIncluded,
                    labels, rawRfc822, mimeMessage, 0, 0, 0, 0, 0, 0);
        }

        ReceivedMessage(String id, String threadId, String historyId, long internalDate,
                        long sizeEstimate, String snippet, boolean bodyIncluded,
                        List<String> labels, byte[] rawRfc822, MimeMessage mimeMessage,
                        int bodyParts, int incompleteBodyParts, int fetchedBodyParts,
                        int attachmentParts, int attachmentIdParts, int inlineDataAttachmentParts) {
            this.id = id;
            this.threadId = threadId;
            this.historyId = historyId;
            this.internalDate = internalDate;
            this.sizeEstimate = sizeEstimate;
            this.snippet = snippet;
            this.bodyIncluded = bodyIncluded;
            this.bodyParts = bodyParts;
            this.incompleteBodyParts = incompleteBodyParts;
            this.fetchedBodyParts = fetchedBodyParts;
            this.attachmentParts = attachmentParts;
            this.attachmentIdParts = attachmentIdParts;
            this.inlineDataAttachmentParts = inlineDataAttachmentParts;
            this.labels = Collections.unmodifiableList(new ArrayList<>(labels));
            this.rawRfc822 = rawRfc822;
            this.mimeMessage = mimeMessage;
        }
    }

    static final class MessageMetadata {
        final String id;
        final String threadId;
        final long internalDate;
        final List<String> labels;

        MessageMetadata(String id, String threadId, long internalDate, List<String> labels) {
            this.id = id;
            this.threadId = threadId;
            this.internalDate = internalDate;
            this.labels = Collections.unmodifiableList(new ArrayList<>(labels));
        }
    }

    private static String readResponse(InputStream input) throws IOException {
        return readResponse(input, Integer.MAX_VALUE);
    }

    static String readResponse(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int length = input.read(buffer); length != -1; length = input.read(buffer)) {
            total += length;
            if (total > maxBytes)
                throw new IOException("Gmail API response exceeded the local size limit");
            output.write(buffer, 0, length);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
