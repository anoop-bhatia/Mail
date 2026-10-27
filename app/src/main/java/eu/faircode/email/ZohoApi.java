package eu.faircode.email;


import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.net.ssl.HttpsURLConnection;

/** Zoho Mail REST transport for receive, send, and explicitly supported mailbox mutations. */
final class ZohoApi {
    static final int PAGE_SIZE = 10;
    static final int MAX_RESPONSE_BYTES = 50 * 1024 * 1024;
    static final int MAX_ATTACHMENT_BYTES = 30 * 1024 * 1024;
    static final String ORDINARY_ATTACHMENT_TYPE = "zoho-attachment";
    static final String INLINE_ATTACHMENT_TYPE = "zoho-inline";
    static final String ZERO_BYTE_ATTACHMENT_ERROR = "Zoho Mail does not accept zero-byte attachments. Remove the empty attachment or choose a non-empty file, then try sending again.";
    private static final int MAX_ERROR_RESPONSE_BYTES = 16 * 1024;

    interface ConnectionFactory {
        HttpsURLConnection open(URL url) throws IOException;
    }

    interface TokenRefresher {
        String refresh(String rejectedToken) throws IOException, JSONException;
    }

    private interface BodyWriter {
        void write(OutputStream output) throws IOException;
    }

    static final class Account {
        final String id;
        final String email;
        final String displayName;

        Account(String id, String email, String displayName) {
            this.id = id;
            this.email = email;
            this.displayName = displayName;
        }
    }

    static final class Folder {
        final String id;
        final String name;
        final String type;
        final boolean selectable;

        Folder(String id, String name, String type, boolean selectable) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.selectable = selectable;
        }
    }

    static final class MailMessage {
        final String id;
        final boolean seen;
        final boolean flagged;
        final long received;
        final long sent;
        final long size;
        final int position;
        final String summary;
        final String threadId;

        MailMessage(String id, boolean seen, boolean flagged, long received, long sent, long size) {
            this(id, seen, flagged, received, sent, size, -1, null);
        }

        MailMessage(String id, boolean seen, boolean flagged, long received, long sent, long size, int position) {
            this(id, seen, flagged, received, sent, size, position, null);
        }

        MailMessage(String id, boolean seen, boolean flagged, long received, long sent,
                    long size, int position, String summary) {
            this(id, seen, flagged, received, sent, size, position, summary, null);
        }

        MailMessage(String id, boolean seen, boolean flagged, long received, long sent,
                    long size, int position, String summary, String threadId) {
            this.id = id;
            this.seen = seen;
            this.flagged = flagged;
            this.received = received;
            this.sent = sent;
            this.size = size;
            this.position = position;
            this.summary = summary;
            this.threadId = threadId;
        }
    }

    static final class Page {
        final List<MailMessage> messages;
        final int start;
        final int limit;
        final int resultItemCount;
        final int httpStatus;

        Page(List<MailMessage> messages, int start, int limit, int resultItemCount, int httpStatus) {
            this.messages = messages;
            this.start = start;
            this.limit = limit;
            this.resultItemCount = resultItemCount;
            this.httpStatus = httpStatus;
        }

        boolean hasMore() {
            return messages.size() >= limit;
        }
    }

    static final class ApiException extends IOException {
        final int status;
        final long retryAfterMs;
        final String requestStage;
        final String errorCode;
        final String errorDescription;

        ApiException(int status, long retryAfterMs) {
            this(status, retryAfterMs, null, null);
        }

        ApiException(int status, long retryAfterMs, String requestStage, SafeErrorDetails details) {
            super(formatErrorMessage(status, requestStage, details));
            this.status = status;
            this.retryAfterMs = retryAfterMs;
            this.requestStage = requestStage;
            this.errorCode = (details == null ? null : details.code);
            this.errorDescription = (details == null ? null : details.description);
        }

        boolean isRateLimitFailure() {
            return status == 429 || (status == 403 && retryAfterMs > 0);
        }

        boolean isPermanentRequestFailure() {
            return status == 400;
        }
    }

    private static final class SafeErrorDetails {
        final String code;
        final String description;

        SafeErrorDetails(String code, String description) {
            this.code = code;
            this.description = description;
        }
    }

    private static String formatErrorMessage(int status, String requestStage, SafeErrorDetails details) {
        StringBuilder message = new StringBuilder("Zoho Mail API HTTP ").append(status);
        if (requestStage != null)
            message.append(" stage=").append(requestStage);
        if (details != null) {
            if (details.code != null)
                message.append(" code=").append(details.code);
            if (details.description != null)
                message.append(" description=").append(details.description);
        }
        return message.toString();
    }

    private static final class UploadedAttachment {
        final String storeName;
        final String attachmentPath;
        final String attachmentName;

        UploadedAttachment(JSONObject json) throws IOException {
            storeName = json.optString("storeName", null);
            attachmentPath = json.optString("attachmentPath", null);
            attachmentName = json.optString("attachmentName", null);
            if (isEmpty(storeName) || isEmpty(attachmentPath) ||
                    isEmpty(attachmentName))
                throw new IOException("Zoho attachment upload returned incomplete metadata");
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("storeName", storeName);
            json.put("attachmentPath", attachmentPath);
            json.put("attachmentName", attachmentName);
            return json;
        }
    }

    private static final class BodyContent {
        String html;
        String plain;
        final List<Part> attachments = new ArrayList<>();
    }

    private static final class UploadContext implements ZohoUploadTimeouts.Diagnostic {
        private static final long LOG_PROGRESS_INTERVAL = 1024L * 1024L;
        final long totalBytes;
        private volatile HttpsURLConnection connection;
        private String lastStage;
        private long nextProgressLog;

        UploadContext(long totalBytes) {
            this.totalBytes = totalBytes;
        }

        void setConnection(HttpsURLConnection connection) {
            this.connection = connection;
        }

        void abort() {
            HttpsURLConnection active = connection;
            if (active != null)
                active.disconnect();
        }

        @Override
        public synchronized void onUpdate(String stage, long elapsedMillis, long bytesSent) {
            if (!stage.equals(lastStage) || bytesSent >= nextProgressLog) {
                org.tinylog.Logger.tag("fairemail").info("Zoho attachment upload stage=" + stage +
                        " elapsed_ms=" + elapsedMillis +
                        " bytes_sent=" + bytesSent +
                        " bytes_total=" + totalBytes);
                lastStage = stage;
                nextProgressLog = ((bytesSent / LOG_PROGRESS_INTERVAL) + 1L) * LOG_PROGRESS_INTERVAL;
            }
        }
    }

    private final String accountId;
    private final String apiBase;
    private final ConnectionFactory connections;
    private String accessToken;
    private TokenRefresher tokenRefresher;
    private int lastHttpStatus = -1;
    private Context protocolContext;
    private long exchangeSequence;
    private long lastExchangeSequence;
    private volatile long lastContentRequestSequence;
    private long lastResponseContentLength = -1;
    private long lastResponseBytes = -1;
    private long lastResponseChars = -1;
    private String lastResponseContentType;

    ZohoApi(String accessToken, String apiDomain) {
        this(accessToken, apiDomain, null, url -> (HttpsURLConnection) url.openConnection());
    }

    ZohoApi(String accessToken, String apiDomain, String accountId) {
        this(accessToken, apiDomain, accountId, url -> (HttpsURLConnection) url.openConnection());
    }

    ZohoApi(String accessToken, String apiDomain, String accountId, ConnectionFactory connections) {
        if (isEmpty(accessToken))
            throw new IllegalArgumentException("Missing Zoho access token");
        this.accessToken = accessToken;
        this.apiBase = ZohoOAuth.getMailApiBase(apiDomain);
        this.accountId = accountId;
        this.connections = connections;
    }

    void setTokenRefresher(TokenRefresher refresher) {
        this.tokenRefresher = refresher;
    }

    void setProtocolLogContext(Context context) {
        this.protocolContext = context;
    }

    int getLastHttpStatus() {
        return lastHttpStatus;
    }

    long getLastContentRequestSequence() {
        return lastContentRequestSequence;
    }

    long getLastResponseContentLength() {
        return lastResponseContentLength;
    }

    long getLastResponseBytes() {
        return lastResponseBytes;
    }

    List<Account> listAccounts() throws IOException, JSONException {
        JSONObject response = requestJson("GET", "/api/accounts", null);
        JSONArray rows = getArray(response, "data", "accounts", "account");
        List<Account> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null)
                continue;
            String id = first(item, "accountId", "account_id");
            String email = first(item, "primaryEmailAddress", "mailboxAddress", "incomingUserName");
            String name = first(item, "displayName", "accountDisplayName", "accountName");
            if (!isEmpty(id) && !isEmpty(email))
                result.add(new Account(id, email, name));
        }
        if (result.isEmpty())
            throw new IOException("Zoho account list did not include a mailbox address");
        return result;
    }

    List<Folder> listFolders() throws IOException, JSONException {
        requireAccount();
        JSONObject response = requestJson("GET", "/api/accounts/" + segment(accountId) + "/folders", null);
        JSONArray rows = getArray(response, "data", "folders", "folder");
        List<Folder> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null)
                continue;
            String id = first(item, "folderId", "folder_id");
            String name = first(item, "folderName", "name");
            String type = first(item, "folderType", "type");
            if (isEmpty(id) || isEmpty(name))
                continue;
            boolean selectable = !"Templates".equalsIgnoreCase(type) &&
                    !"Snoozed".equalsIgnoreCase(type) && !"Outbox".equalsIgnoreCase(type);
            result.add(new Folder(id, name, type, selectable));
        }
        return result;
    }

    Page listMessages(String folderId, int start, int limit) throws IOException, JSONException {
        requireAccount();
        if (start < 1 || limit < 1 || limit > PAGE_SIZE)
            throw new IllegalArgumentException("Zoho page must be bounded to ten messages");
        StringBuilder path = new StringBuilder("/api/accounts/")
                .append(segment(accountId)).append("/messages/view?start=").append(start)
                .append("&limit=").append(limit).append("&sortBy=date&sortorder=false");
        if (!isEmpty(folderId))
            path.append("&folderId=").append(segment(folderId));
        JSONObject response = requestJson("GET", path.toString(), null);
        JSONArray rows = getArray(response, "data", "messages", "message");
        List<MailMessage> messages = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null)
                continue;
            String id = first(item, "messageId", "message_id", "id");
            if (isEmpty(id))
                continue;
            String status = first(item, "status", "readStatus");
            boolean seen = isSeen(status, item);
            boolean flagged = isFlagged(item);
            long received = firstLong(item, "receivedTime", "receivedDate", "receivedDateInGMT", "date");
            long sent = firstLong(item, "sentDateInGMT", "sentDate", "sentTime");
            long size = firstLong(item, "size", "messageSize", "totalSize");
            String summary = first(item, "summary", "snippet", "preview");
            String threadId = first(item, "threadId", "thread_id");
            messages.add(new MailMessage(id, seen, flagged, received, sent, size, i, summary, threadId));
        }
        return new Page(messages, start, limit, rows.length(), lastHttpStatus);
    }

    byte[] getOriginalMessage(String messageId) throws IOException, JSONException {
        requireAccount();
        JSONObject response = requestJson("GET", "/api/accounts/" + segment(accountId) +
                "/messages/" + segment(messageId) + "/originalmessage", null);
        JSONObject data = response.optJSONObject("data");
        String content = (data == null ? null : data.optString("content", null));
        if (isEmpty(content))
            throw new IOException("Zoho original-message response did not contain MIME content");
        return content.getBytes(StandardCharsets.UTF_8);
    }

    String getMessageHeaders(String folderId, String messageId) throws IOException, JSONException {
        requireAccount();
        JSONObject response = requestJson("GET", messagePath(folderId, messageId) + "/header?raw=true", null);
        JSONObject data = response.optJSONObject("data");
        String headers = (data == null ? null : data.optString("headerContent", null));
        if (isEmpty(headers))
            throw new IOException("Zoho message-header response did not contain raw headers");
        return headers;
    }

    String getMessageContent(String folderId, String messageId) throws IOException, JSONException {
        return getMessageContent(folderId, messageId, null);
    }

    String getMessageContent(String folderId, String messageId, String traceId)
            throws IOException, JSONException {
        requireAccount();
        lastContentRequestSequence = -1;
        lastResponseContentLength = -1;
        lastResponseBytes = -1;
        lastResponseChars = -1;
        lastResponseContentType = null;
        String path = messagePath(folderId, messageId) + "/content?includeBlockContent=true";
        ZohoApiDiagnostics.logMessageScope(protocolContext, "content", "GET", path,
                folderId, messageId);
        JSONObject response = requestJson("GET", path, null, traceId);
        lastContentRequestSequence = lastExchangeSequence;
        JSONObject data = response.optJSONObject("data");
        Object rawContent = (data == null ? null : data.opt("content"));
        boolean contentIsString = rawContent instanceof String;
        String content = (contentIsString ? (String) rawContent :
                (data == null || rawContent == null ? null : data.optString("content", null)));
        ZohoApiDiagnostics.logTraceContentResponse(protocolContext, traceId,
                lastContentRequestSequence, lastResponseContentLength, lastResponseBytes,
                lastResponseChars, lastResponseContentType, data != null, contentIsString, content);
        if (content == null)
            throw new IOException("Zoho message-content response did not contain content");
        return content;
    }

    List<NativeApiContent.Attachment> getMessageAttachments(String folderId, String messageId)
            throws IOException, JSONException {
        return getMessageAttachments(folderId, messageId, null);
    }

    List<NativeApiContent.Attachment> getMessageAttachments(String folderId, String messageId,
                                                             String traceId)
            throws IOException, JSONException {
        requireAccount();
        String path = messagePath(folderId, messageId) + "/attachmentinfo?includeInline=true";
        ZohoApiDiagnostics.logMessageScope(protocolContext, "attachment_info", "GET", path,
                folderId, messageId);
        JSONObject response = requestJson("GET", path, null, traceId);
        JSONObject data = response.optJSONObject("data");
        if (data == null)
            throw new IOException("Zoho attachment-info response did not contain data");
        List<NativeApiContent.Attachment> attachments = new ArrayList<>();
        addAttachmentInfo(attachments, data.optJSONArray("attachments"), folderId, false);
        addAttachmentInfo(attachments, data.optJSONArray("inline"), folderId, true);
        int ordinaryCount = 0;
        int inlineCount = 0;
        int inlineImageCount = 0;
        for (NativeApiContent.Attachment attachment : attachments)
            if (INLINE_ATTACHMENT_TYPE.equals(attachment.providerType)) {
                inlineCount++;
                if (attachment.contentType != null &&
                        attachment.contentType.regionMatches(true, 0, "image/", 0, 6))
                    inlineImageCount++;
            } else if (ORDINARY_ATTACHMENT_TYPE.equals(attachment.providerType))
                ordinaryCount++;
        if (traceId != null)
            ZohoApiDiagnostics.logTrace(protocolContext, traceId, "content_response",
                    "attachment_info_seq=" + Math.max(0, lastExchangeSequence) +
                            " ordinary_attachments=" + ordinaryCount +
                            " inline_resources=" + inlineCount +
                            " inline_images=" + inlineImageCount);
        else
            ZohoApiDiagnostics.logAttachmentInfo(protocolContext, lastExchangeSequence,
                    ordinaryCount, inlineCount, inlineImageCount);
        return attachments;
    }

    void downloadAttachment(String folderId, String messageId, String attachmentId,
                           File destination) throws IOException {
        requireAccount();
        if (destination == null)
            throw new IllegalArgumentException("Missing Zoho attachment destination");
        requestBinaryToFile("GET", messagePath(folderId, messageId) + "/attachments/" +
                segment(attachmentId), "application/octet-stream", destination);
    }

    void downloadInlineImage(String folderId, String messageId, String contentId,
                             String fileName, File destination) throws IOException {
        downloadInlineImage(folderId, messageId, contentId, fileName, destination, null);
    }

    void downloadInlineImage(String folderId, String messageId, String contentId,
                             String fileName, File destination,
                             EntityAttachment metadataRow) throws IOException {
        requireAccount();
        if (destination == null)
            throw new IllegalArgumentException("Missing Zoho inline image destination");
        String cid = (contentId == null ? null : contentId.trim());
        if (cid != null && cid.length() >= 2 && cid.startsWith("<") && cid.endsWith(">"))
            cid = cid.substring(1, cid.length() - 1);
        if (isEmpty(cid))
            throw new IllegalArgumentException("Missing Zoho inline image content ID");
        String path = messagePath(folderId, messageId) + "/inline?contentId=" + encodeComponent(cid);
        if (!isEmpty(fileName))
            path += "&fileName=" + encodeComponent(fileName);
        ZohoApiDiagnostics.InlineRequestCorrelation correlation = (metadataRow == null ? null :
                new ZohoApiDiagnostics.InlineRequestCorrelation(accountId, folderId, messageId, cid,
                        metadataRow.id, metadataRow.message, metadataRow.provider_id,
                        metadataRow.provider_folder, metadataRow.cid));
        requestBinaryToFile("GET", path, "*/*", destination,
                ZohoApiDiagnostics.inlineScopeTag(folderId, messageId),
                ZohoApiDiagnostics.inlineCidTag(contentId), correlation, null);
    }

    private String messagePath(String folderId, String messageId) {
        return "/api/accounts/" + segment(accountId) + "/folders/" + segment(folderId) +
                "/messages/" + segment(messageId);
    }

    static void addAttachmentInfo(List<NativeApiContent.Attachment> result,
                                  JSONArray rows, String folderId, boolean inline)
            throws JSONException {
        if (rows == null)
            return;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null)
                continue;
            String id = first(item, "attachmentId", "id");
            if (isEmpty(id))
                continue;
            String name = first(item, "attachmentName", "name");
            String cid = first(item, "cid", "contentId");
            long size = firstLong(item, "attachmentSize", "size");
            String type = first(item, "contentType", "mimeType");
            if (inline)
                type = inferInlineImageType(type, name);
            result.add(new NativeApiContent.Attachment(id, folderId,
                    inline ? INLINE_ATTACHMENT_TYPE : ORDINARY_ATTACHMENT_TYPE,
                    name, type, (inline ? Part.INLINE : Part.ATTACHMENT), cid,
                    (size > 0 ? size : null), null, inline));
        }
    }

    // Zoho's documented attachment-info schema omits media types for inline entries.
    // Infer only recognizable image extensions; ordinary attachments remain untouched.
    private static String inferInlineImageType(String type, String name) {
        String normalized = (type == null ? null : type.trim());
        if (normalized != null && !normalized.isEmpty() &&
                !"application/octet-stream".equalsIgnoreCase(normalized) &&
                !"application/x-unknown-content-type".equalsIgnoreCase(normalized) &&
                !"*/*".equals(normalized))
            return normalized;

        if (name == null)
            return normalized;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1)
            return normalized;

        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "bmp":
                return "image/bmp";
            case "svg":
                return "image/svg+xml";
            case "tif":
            case "tiff":
                return "image/tiff";
            case "avif":
                return "image/avif";
            case "heic":
                return "image/heic";
            case "heif":
                return "image/heif";
            case "ico":
                return "image/x-icon";
            default:
                return normalized;
        }
    }

    private void requestBinaryToFile(String method, String path, String accept, File destination)
            throws IOException {
        requestBinaryToFile(method, path, accept, destination, null, null);
    }

    private void requestBinaryToFile(String method, String path, String accept, File destination,
                                     String scopeTag, String cidTag) throws IOException {
        requestBinaryToFile(method, path, accept, destination, scopeTag, cidTag, null);
    }

    private void requestBinaryToFile(String method, String path, String accept, File destination,
                                     String scopeTag, String cidTag,
                                     ZohoApiDiagnostics.InlineRequestCorrelation inlineCorrelation)
            throws IOException {
        requestBinaryToFile(method, path, accept, destination, scopeTag, cidTag,
                inlineCorrelation, "application/json");
    }

    private void requestBinaryToFile(String method, String path, String accept, File destination,
                                     String scopeTag, String cidTag,
                                     ZohoApiDiagnostics.InlineRequestCorrelation inlineCorrelation,
                                     String requestContentType) throws IOException {
        lastHttpStatus = -1;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpsURLConnection connection = null;
            long started = System.currentTimeMillis();
            long sequence = ++exchangeSequence;
            lastExchangeSequence = sequence;
            int status = -1;
            String responseType = null;
            long responseBytes = -1;
            long responseContentLength = -1;
            InlineErrorResponse inlineError = null;
            boolean logged = false;
            try {
                connection = connections.open(new URL(apiBase + path));
                connection.setRequestMethod(method);
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", accept);
                if (requestContentType != null)
                    connection.setRequestProperty("Content-Type", requestContentType);
                connection.setRequestProperty("Authorization", "Zoho-oauthtoken " + accessToken);
                if (scopeTag != null && cidTag != null)
                    ZohoApiDiagnostics.logInlineRequestContext(protocolContext, sequence,
                            attempt + 1, method, path, connection.getRequestProperty("Accept"),
                            connection.getRequestProperty("Content-Type"), scopeTag, cidTag,
                            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                            BuildConfig.GIT_COMMIT);
                status = connection.getResponseCode();
                lastHttpStatus = status;
                responseType = connection.getContentType();
                if (status == HttpsURLConnection.HTTP_UNAUTHORIZED && attempt == 0 &&
                        tokenRefresher != null) {
                    ZohoApiDiagnostics.logBinaryExchange(protocolContext, sequence, method, path,
                            attempt + 1, status, System.currentTimeMillis() - started,
                            responseType, -1, "auth_retry");
                    logged = true;
                    String rejected = accessToken;
                    String refreshed;
                    try {
                        refreshed = tokenRefresher.refresh(rejected);
                    } catch (JSONException ex) {
                        throw new IOException("Zoho attachment authorization refresh failed", ex);
                    }
                    if (isEmpty(refreshed) || refreshed.equals(rejected))
                        throw new IOException("Zoho requires account reauthorization");
                    accessToken = refreshed;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    SafeErrorDetails details;
                    if (status == HttpsURLConnection.HTTP_NOT_ACCEPTABLE &&
                            path.contains("/inline?contentId=")) {
                        inlineError = readInlineErrorResponse(connection);
                        responseBytes = inlineError.responseBytes;
                        responseContentLength = inlineError.contentLength;
                        ZohoApiDiagnostics.JsonErrorSummary summary =
                                ZohoApiDiagnostics.summarizeInlineError(status,
                                        inlineError.body, inlineError.readState);
                        details = new SafeErrorDetails(summary.code, summary.reason);
                    } else
                        details = readSafeApiError(status, connection);
                    throw new ApiException(status, getRetryAfter(connection), null, details);
                }
                try (InputStream input = connection.getInputStream();
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                    copy(input, output, MAX_ATTACHMENT_BYTES);
                    output.flush();
                    responseBytes = destination.length();
                }
                ZohoApiDiagnostics.logBinaryExchange(protocolContext, sequence, method, path,
                        attempt + 1, status, System.currentTimeMillis() - started,
                        responseType, responseBytes, "success");
                logged = true;
                return;
            } catch (IOException ex) {
                if (!logged) {
                    ApiException apiException = (ex instanceof ApiException ? (ApiException) ex : null);
                    if (inlineError != null)
                        ZohoApiDiagnostics.logInlineHttpErrorExchange(protocolContext, sequence,
                                method, path, attempt + 1, status,
                                System.currentTimeMillis() - started, responseType, responseBytes,
                                responseContentLength, inlineError.body, inlineError.readState);
                    else
                        ZohoApiDiagnostics.logBinaryExchange(protocolContext, sequence, method, path,
                                attempt + 1, status, System.currentTimeMillis() - started,
                                responseType, responseBytes,
                                (status >= 400 ? "http_error" : "io_error"),
                                (apiException == null ? null : apiException.errorCode),
                                (apiException == null ? null : apiException.errorDescription));
                    if (inlineCorrelation != null && inlineError != null)
                        ZohoApiDiagnostics.logInline406Correlation(protocolContext, sequence,
                                attempt + 1, method, path, status,
                                System.currentTimeMillis() - started, responseType, responseBytes,
                                responseContentLength, inlineError.body, inlineError.readState,
                                (connection == null ? null : connection.getRequestProperty("Accept")),
                                (connection == null ? null : connection.getRequestProperty("Content-Type")),
                                inlineCorrelation);
                }
                if (destination.exists() && !destination.delete())
                    Log.w("Zoho attachment temporary-file cleanup failed: " + destination);
                throw ex;
            } finally {
                if (connection != null)
                    connection.disconnect();
            }
        }
        throw new IOException("Zoho attachment authorization retry exhausted");
    }

    private static final class InlineErrorResponse {
        final long responseBytes;
        final long contentLength;
        final String body;
        final String readState;

        InlineErrorResponse(long responseBytes, long contentLength, String body, String readState) {
            this.responseBytes = responseBytes;
            this.contentLength = contentLength;
            this.body = body;
            this.readState = readState;
        }
    }

    private static InlineErrorResponse readInlineErrorResponse(HttpsURLConnection connection) {
        long contentLength = parseContentLength(connection.getHeaderField("Content-Length"));
        InputStream input = connection.getErrorStream();
        if (input == null)
            return new InlineErrorResponse(0, contentLength, null, "empty");

        ByteArrayOutputStream retained = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        long total = 0;
        boolean oversized = false;
        try (InputStream error = input) {
            for (int length = error.read(buffer); length != -1; length = error.read(buffer)) {
                total = (total > Long.MAX_VALUE - length ? Long.MAX_VALUE : total + length);
                if (!oversized && retained.size() + length <= MAX_ERROR_RESPONSE_BYTES)
                    retained.write(buffer, 0, length);
                else
                    oversized = true;
            }
            String body = (oversized ? null : new String(retained.toByteArray(), StandardCharsets.UTF_8));
            return new InlineErrorResponse(total, contentLength, body,
                    (oversized ? "oversized" : (total == 0 ? "empty" : "complete")));
        } catch (IOException ex) {
            return new InlineErrorResponse(total, contentLength, null, "read_error");
        }
    }

    private static long parseContentLength(String value) {
        if (value == null)
            return -1;
        try {
            long length = Long.parseLong(value.trim());
            return (length >= 0 ? length : -1);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    JSONObject send(MimeMessage message) throws IOException, JSONException, MessagingException {
        return send(message, null);
    }

    JSONObject send(MimeMessage message, String replyMessageId)
            throws IOException, JSONException, MessagingException {
        requireAccount();
        if (message == null)
            throw new IllegalArgumentException("Missing message");
        if (!isEmpty(replyMessageId))
            remoteId(replyMessageId);
        BodyContent body = new BodyContent();
        collectBody(message, body);
        if (hasZeroByteAttachment(body.attachments))
            throw new IOException(ZERO_BYTE_ATTACHMENT_ERROR);
        Address[] from = message.getFrom();
        String fromAddress = addressString(from);
        if (isEmpty(fromAddress))
            throw new MessagingException("Zoho send requires a From address");

        JSONObject payload = new JSONObject();
        payload.put("fromAddress", fromAddress);
        payload.put("toAddress", addressString(message.getRecipients(Message.RecipientType.TO)));
        String cc = addressString(message.getRecipients(Message.RecipientType.CC));
        String bcc = addressString(message.getRecipients(Message.RecipientType.BCC));
        if (!isEmpty(cc))
            payload.put("ccAddress", cc);
        if (!isEmpty(bcc))
            payload.put("bccAddress", bcc);
        payload.put("subject", (message.getSubject() == null ? "" : message.getSubject()));
        boolean html = body.html != null;
        payload.put("content", (html ? body.html : (body.plain == null ? "" : body.plain)));
        payload.put("mailFormat", html ? "html" : "plaintext");
        payload.put("encoding", "UTF-8");
        if (!isEmpty(replyMessageId))
            payload.put("action", "Reply");
        if (Boolean.TRUE.equals(message.getHeader("Disposition-Notification-To") != null))
            payload.put("askReceipt", "yes");

        if (!body.attachments.isEmpty()) {
            JSONArray attachments = new JSONArray();
            for (Part part : body.attachments)
                attachments.put(uploadAttachment(part).toJson());
            payload.put("attachments", attachments);
        }
        String path = "/api/accounts/" + segment(accountId) + "/messages" +
                (isEmpty(replyMessageId) ? "" : "/" + segment(replyMessageId));
        return requestJson("POST", path, payload);
    }

    static String getSentMessageId(JSONObject response) {
        JSONObject data = (response == null ? null : response.optJSONObject("data"));
        String messageId = (data == null ? null : first(data, "messageId"));
        return (isValidRemoteId(messageId) ? messageId : null);
    }

    void setMessageRead(String messageId, boolean seen) throws IOException, JSONException {
        requireAccount();
        JSONObject payload = new JSONObject();
        payload.put("mode", (seen ? "markAsRead" : "markAsUnread"));
        payload.put("messageId", new JSONArray().put(remoteId(messageId)));
        requestJson("PUT", "/api/accounts/" + segment(accountId) + "/updatemessage", payload);
    }

    void setMessageFlagged(String sourceFolderId, String messageId, boolean flagged)
            throws IOException, JSONException {
        requireAccount();
        JSONObject payload = new JSONObject();
        payload.put("mode", "setFlag");
        payload.put("messageId", new JSONArray().put(remoteId(messageId)));
        payload.put("flagid", (flagged ? "important" : "flag_not_set"));
        payload.put("isFolderSpecific", true);
        payload.put("folderId", remoteId(sourceFolderId));
        requestJson("PUT", "/api/accounts/" + segment(accountId) + "/updatemessage", payload);
    }

    void moveMessage(String sourceFolderId, String messageId, String destinationFolderId)
            throws IOException, JSONException {
        requireAccount();
        JSONObject payload = new JSONObject();
        payload.put("mode", "moveMessage");
        payload.put("messageId", new JSONArray().put(remoteId(messageId)));
        payload.put("destfolderId", remoteId(destinationFolderId));
        payload.put("isFolderSpecific", true);
        payload.put("folderId", remoteId(sourceFolderId));
        requestJson("PUT", "/api/accounts/" + segment(accountId) + "/updatemessage", payload);
    }

    void deleteMessage(String sourceFolderId, String messageId, boolean expunge)
            throws IOException, JSONException {
        requireAccount();
        long folder = remoteId(sourceFolderId);
        long message = remoteId(messageId);
        String path = "/api/accounts/" + segment(accountId) + "/folders/" +
                folder + "/messages/" + message + "?expunge=" + expunge;
        ApiMutationLog.zohoDeleteRequest(expunge,
                isValidRemoteId(sourceFolderId), isValidRemoteId(messageId));
        requestJson("DELETE", path, null);
    }

    void emptyFolder(String folderId) throws IOException, JSONException {
        requireAccount();
        long remoteFolder = remoteId(folderId);
        JSONObject payload = new JSONObject();
        payload.put("mode", "emptyFolder");
        requestJson("PUT", "/api/accounts/" + segment(accountId) + "/folders/" +
                remoteFolder, payload);
    }

    static boolean isValidRemoteId(String value) {
        if (isEmpty(value) || !value.matches("[0-9]+"))
            return false;
        try {
            return Long.parseLong(value) >= 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static long remoteId(String value) {
        if (!isValidRemoteId(value))
            throw new IllegalArgumentException("Missing Zoho remote identifier");
        try {
            long id = Long.parseLong(value);
            if (id < 0)
                throw new NumberFormatException();
            return id;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid Zoho remote identifier");
        }
    }

    static boolean hasZeroByteAttachment(MimeMessage message) throws IOException, MessagingException {
        BodyContent body = new BodyContent();
        collectBody(message, body);
        return hasZeroByteAttachment(body.attachments);
    }

    private static boolean hasZeroByteAttachment(List<Part> attachments) throws MessagingException {
        for (Part part : attachments)
            if (part.getSize() == 0)
                return true;
        return false;
    }

    private UploadedAttachment uploadAttachment(Part part) throws IOException, JSONException, MessagingException {
        requireAccount();
        long size = part.getSize();
        if (size > MAX_ATTACHMENT_BYTES)
            throw new IOException("Zoho attachment exceeds the safe 30 MiB per-file limit");
        if (size == 0)
            throw new IOException(ZERO_BYTE_ATTACHMENT_ERROR);
        String name = part.getFileName();
        if (isEmpty(name))
            name = "attachment";
        name = name.replace('/', '_').replace('\\', '_').replace('\r', '_').replace('\n', '_');
        String disposition = part.getDisposition();
        boolean inline = Part.INLINE.equalsIgnoreCase(disposition) ||
                (part.getHeader("Content-ID") != null && !Part.ATTACHMENT.equalsIgnoreCase(disposition));
        String path = "/api/accounts/" + segment(accountId) + "/messages/attachments?fileName=" +
                encodeComponent(name) + "&isInline=" + inline;
        UploadContext upload = new UploadContext(size);
        long overallTimeout = ZohoUploadTimeouts.budgetMillis(size);
        JSONObject response = ZohoUploadTimeouts.execute(progress -> {
            JSONObject uploaded = request("POST", path, "application/octet-stream", output -> {
                try {
                    try (InputStream input = part.getInputStream()) {
                        copy(input, output, MAX_ATTACHMENT_BYTES, progress);
                    }
                } catch (MessagingException ex) {
                    throw new IOException("Zoho attachment could not be read");
                }
            }, size, upload, progress);
            return uploaded;
        }, upload::abort, overallTimeout, ZohoUploadTimeouts.READ_TIMEOUT_MILLIS, upload);
        JSONObject data = response.optJSONObject("data");
        if (data == null)
            throw new IOException("Zoho attachment upload returned no metadata");
        return new UploadedAttachment(data);
    }

    private JSONObject requestJson(String method, String path, JSONObject json)
            throws IOException, JSONException {
        return requestJson(method, path, json, null);
    }

    private JSONObject requestJson(String method, String path, JSONObject json, String traceId)
            throws IOException, JSONException {
        byte[] body = (json == null ? null : json.toString().getBytes(StandardCharsets.UTF_8));
        return request(method, path, (body == null ? null : "application/json; charset=UTF-8"),
                (body == null ? null : output -> output.write(body)),
                (body == null ? -1 : body.length), null, null, traceId);
    }

    private JSONObject request(String method, String path, String contentType,
                               BodyWriter body, long bodyLength) throws IOException, JSONException {
        return request(method, path, contentType, body, bodyLength, null, null, null);
    }

    private JSONObject request(String method, String path, String contentType,
                               BodyWriter body, long bodyLength, UploadContext upload,
                               ZohoUploadTimeouts.Progress progress) throws IOException, JSONException {
        return request(method, path, contentType, body, bodyLength, upload, progress, null);
    }

    private JSONObject request(String method, String path, String contentType,
                               BodyWriter body, long bodyLength, UploadContext upload,
                               ZohoUploadTimeouts.Progress progress, String traceId)
            throws IOException, JSONException {
        lastHttpStatus = -1;
        lastResponseContentLength = -1;
        lastResponseBytes = -1;
        lastResponseChars = -1;
        lastResponseContentType = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            lastHttpStatus = -1;
            HttpsURLConnection connection = null;
            long started = System.currentTimeMillis();
            long sequence = ++exchangeSequence;
            lastExchangeSequence = sequence;
            int status = -1;
            String responseType = null;
            long responseChars = -1;
            boolean logged = false;
            try {
                URL url = new URL(apiBase + path);
                connection = connections.open(url);
                if (upload != null) {
                    upload.setConnection(connection);
                    progress.stage("connecting");
                }
                connection.setRequestMethod(method);
                connection.setConnectTimeout(upload == null
                        ? 20000 : ZohoUploadTimeouts.CONNECT_TIMEOUT_MILLIS);
                connection.setReadTimeout(upload == null
                        ? 30000 : ZohoUploadTimeouts.READ_TIMEOUT_MILLIS);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Zoho-oauthtoken " + accessToken);
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", contentType);
                    if (bodyLength >= 0)
                        connection.setFixedLengthStreamingMode(bodyLength);
                    else
                        connection.setChunkedStreamingMode(8192);
                    try (OutputStream output = connection.getOutputStream()) {
                        if (upload != null)
                            progress.stage("writing");
                        body.write(output);
                    }
                }
                if (upload != null)
                    progress.stage("waiting_response");
                status = connection.getResponseCode();
                lastHttpStatus = status;
                responseType = connection.getContentType();
                lastResponseContentType = responseType;
                ApiMutationLog.httpCurrent(status,
                        (status >= 200 && status < 300 ? "success" : (status == 429 ? "rate_limited" : "failure")),
                        (status >= 200 && status < 300 ? "none" : "api"));
                if (status == HttpsURLConnection.HTTP_UNAUTHORIZED && attempt == 0 &&
                        tokenRefresher != null) {
                    ZohoApiDiagnostics.logExchange(protocolContext, sequence, method, path,
                            attempt + 1, status, System.currentTimeMillis() - started,
                            contentType, bodyLength, responseType, -1, "auth_retry", traceId);
                    logged = true;
                    String rejected = accessToken;
                    String refreshed = tokenRefresher.refresh(rejected);
                    if (isEmpty(refreshed) || refreshed.equals(rejected))
                        throw new IOException("Zoho requires account reauthorization");
                    accessToken = refreshed;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    String requestStage = getSendRequestStage(method, path);
                    SafeErrorDetails details = (requestStage == null
                            ? null : readSafeApiError(status, connection));
                    throw new ApiException(status, getRetryAfter(connection), requestStage, details);
                }
                JSONObject response;
                if (upload != null)
                    progress.stage("reading_response");
                try (InputStream input = connection.getInputStream()) {
                    BoundedResponse responseBody = readBounded(input, MAX_RESPONSE_BYTES);
                    responseChars = responseBody.content.length();
                    lastResponseContentLength = connection.getContentLength();
                    lastResponseBytes = responseBody.bytes;
                    lastResponseChars = responseChars;
                    if ("GET".equalsIgnoreCase(method) && path.contains("/messages/") &&
                            path.endsWith("/content?includeBlockContent=true"))
                        ZohoApiDiagnostics.logTraceBodyFetch(protocolContext, traceId, sequence,
                                lastResponseContentLength, lastResponseBytes, responseChars);
                    response = new JSONObject(responseBody.content);
                }
                checkZohoStatus(response);
                ZohoApiDiagnostics.logExchange(protocolContext, sequence, method, path,
                        attempt + 1, status, System.currentTimeMillis() - started,
                        contentType, bodyLength, responseType, responseChars, "success", traceId);
                logged = true;
                return response;
            } catch (ApiException ex) {
                if (!logged)
                    ZohoApiDiagnostics.logExchange(protocolContext, sequence, method, path,
                            attempt + 1, status, System.currentTimeMillis() - started,
                            contentType, bodyLength, responseType, responseChars,
                            (status < 400 ? "api_error" : "http_error"), traceId);
                throw ex;
            } catch (IOException ex) {
                if (!logged)
                    ZohoApiDiagnostics.logExchange(protocolContext, sequence, method, path,
                            attempt + 1, status, System.currentTimeMillis() - started,
                            contentType, bodyLength, responseType, responseChars,
                            (status >= 400 ? "http_error" : "io_error"), traceId);
                ApiMutationLog.httpCurrent(-1, "failure", ApiMutationLog.errorClass(ex));
                throw ex;
            } catch (JSONException ex) {
                if (!logged)
                    ZohoApiDiagnostics.logExchange(protocolContext, sequence, method, path,
                            attempt + 1, status, System.currentTimeMillis() - started,
                            contentType, bodyLength, responseType, responseChars, "parse_error", traceId);
                ApiMutationLog.httpCurrent(lastHttpStatus, "failure", ApiMutationLog.errorClass(ex));
                throw ex;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                    if (upload != null)
                        upload.setConnection(null);
                }
            }
        }
        throw new IOException("Zoho request retry exhausted");
    }

    private static String getSendRequestStage(String method, String path) {
        if (!"POST".equals(method) || !path.startsWith("/api/accounts/"))
            return null;
        if (path.endsWith("/messages") || path.matches(".*/messages/[0-9]+$"))
            return "send";
        if (path.endsWith("/messages/attachments") || path.contains("/messages/attachments?"))
            return "attachment_upload";
        return null;
    }

    private static SafeErrorDetails readSafeApiError(int httpStatus, HttpsURLConnection connection) {
        try (InputStream input = connection.getErrorStream()) {
            if (input == null)
                return null;
            String body = readErrorBody(input);
            if (body == null || body.isEmpty())
                return null;

            JSONObject root = new JSONObject(body);
            JSONObject status = root.optJSONObject("status");
            if (status == null)
                return null;

            String code = safeZohoErrorCode(httpStatus, status.opt("code"));
            String description = safeZohoErrorDescription(httpStatus, status.opt("description"));
            if (code == null && description == null)
                return null;
            return new SafeErrorDetails(code, description);
        } catch (Exception ignored) {
            // A malformed, oversized, or unreadable error body must never replace the HTTP status.
            return null;
        }
    }

    private static String readErrorBody(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        for (int length = input.read(buffer); length != -1; length = input.read(buffer)) {
            if (output.size() + length > MAX_ERROR_RESPONSE_BYTES)
                return null;
            output.write(buffer, 0, length);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String safeZohoErrorCode(int httpStatus, Object value) {
        if (value instanceof Number) {
            double code = ((Number) value).doubleValue();
            if (code == httpStatus && httpStatus >= 400 && httpStatus <= 599)
                return Integer.toString(httpStatus);
        } else if (value instanceof String) {
            String code = ((String) value).trim();
            if (code.matches("[0-9]{3}") && code.equals(Integer.toString(httpStatus)) &&
                    httpStatus >= 400 && httpStatus <= 599)
                return code;
        }
        return null;
    }

    private static String safeZohoErrorDescription(int httpStatus, Object value) {
        if (!(value instanceof String))
            return null;
        String description = (String) value;
        if (description.length() > 512)
            return null;
        String normalized = description.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);

        switch (httpStatus) {
            case 400:
                return ("BAD REQUEST".equals(normalized) ||
                        ("BAD REQUEST - THE INPUT PARAMETERS PROVIDED IN THE REQUEST ARE INVALID OR INCORRECT. " +
                                "THE REQUESTER MUST MODIFY THE REQUEST AND RESEND THE REQUEST.").equals(normalized)
                        ? "BAD_REQUEST" : null);
            case 401:
                return ("UNAUTHORIZED".equals(normalized) ||
                        ("UNAUTHORIZED -THE REQUEST REQUIRES VALID AUTHENTICATION CREDENTIALS, WHICH ARE EITHER " +
                                "MISSING OR INVALID.THE USER MUST PROVIDE PROPER AUTHENTICATION TO ACCESS THE " +
                                "REQUESTED RESOURCE.").equals(normalized)
                        ? "UNAUTHORIZED" : null);
            case 403:
                return ("FORBIDDEN".equals(normalized) ||
                        "FORBIDDEN - THE USER IS AUTHENTICATED BUT NOT AUTHORIZED TO PERFORM THE REQUESTED ACTION.".equals(normalized)
                        ? "FORBIDDEN" : null);
            case 404:
                return ("NOT FOUND".equals(normalized) ||
                        "NOT FOUND - THE REQUESTED RESOURCE COULD NOT BE FOUND OR THE URL IS INVALID.".equals(normalized)
                        ? "NOT_FOUND" : null);
            case 406:
                return ("NOT ACCEPTABLE".equals(normalized) ? "NOT_ACCEPTABLE" : null);
            case 500:
                return ("INTERNAL ERROR".equals(normalized) ||
                        ("INTERNAL ERROR - THE SERVER ENCOUNTERED AN UNEXPECTED ERROR WHILE PROCESSING THE REQUEST. " +
                                "REVIEW THE REQUEST AND TRY AGAIN LATER.").equals(normalized)
                        ? "INTERNAL_ERROR" : null);
            default:
                return null;
        }
    }

    private static void checkZohoStatus(JSONObject response) throws IOException {
        JSONObject status = response.optJSONObject("status");
        if (status == null)
            return;
        int code = status.optInt("code", 200);
        if (code < 200 || code >= 300)
            throw new ApiException(code, 0);
    }

    private static long getRetryAfter(HttpsURLConnection connection) {
        String value = connection.getHeaderField("Retry-After");
        if (value == null)
            return 0;
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds <= 0)
                return 0;
            return (seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static JSONArray getArray(JSONObject response, String key,
                                      String... nestedKeys) throws IOException {
        JSONArray array = response.optJSONArray(key);
        if (array != null)
            return array;
        JSONObject data = response.optJSONObject(key);
        if (data != null)
            for (String nested : nestedKeys) {
                array = data.optJSONArray(nested);
                if (array != null)
                    return array;
            }
        throw new IOException("Zoho response did not contain " + key + " list");
    }

    private static String first(JSONObject json, String... names) {
        for (String name : names) {
            Object value = json.opt(name);
            if (value != null && value != JSONObject.NULL) {
                String text = String.valueOf(value);
                if (!text.isEmpty() && !"null".equalsIgnoreCase(text))
                    return text;
            }
        }
        return null;
    }

    private static long firstLong(JSONObject json, String... names) {
        for (String name : names) {
            Object value = json.opt(name);
            if (value instanceof Number)
                return ((Number) value).longValue();
            if (value != null && value != JSONObject.NULL)
                try {
                    return Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                }
        }
        return 0;
    }

    private static boolean isSeen(String status, JSONObject json) {
        if (status != null) {
            String normalized = status.trim().toLowerCase(Locale.ROOT);
            if ("read".equals(normalized) || "1".equals(normalized) || "true".equals(normalized))
                return true;
            if ("unread".equals(normalized) || "0".equals(normalized) || "false".equals(normalized))
                return false;
        }
        return json.optBoolean("isRead", false);
    }

    private static boolean isFlagged(JSONObject json) {
        String flag = first(json, "flagId", "flagid", "flag");
        if (flag != null)
            try {
                return Integer.parseInt(flag) > 0;
            } catch (NumberFormatException ignored) {
                String normalized = flag.trim().toLowerCase(Locale.ROOT);
                return ("true".equals(normalized) || "flagged".equals(normalized) ||
                        "info".equals(normalized) || "important".equals(normalized) ||
                        "followup".equals(normalized));
            }
        return json.optBoolean("isFlagged", json.optBoolean("flagged", false));
    }

    private static String segment(String value) {
        if (isEmpty(value))
            throw new IllegalArgumentException("Missing Zoho path identifier");
        return encodeComponent(value);
    }

    private static String addressString(Address[] addresses) {
        if (addresses == null || addresses.length == 0)
            return "";
        List<String> values = new ArrayList<>();
        for (Address address : addresses) {
            if (address instanceof InternetAddress) {
                String value = ((InternetAddress) address).getAddress();
                if (!isEmpty(value))
                    values.add(value);
            } else if (address != null && !isEmpty(address.toString()))
                values.add(address.toString());
        }
        return join(values, ",");
    }

    private static void collectBody(Part part, BodyContent body)
            throws IOException, MessagingException {
        String disposition = part.getDisposition();
        String filename = part.getFileName();
        if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || !isEmpty(filename)) {
            body.attachments.add(part);
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Object content = part.getContent();
            if (content instanceof Multipart) {
                Multipart multipart = (Multipart) content;
                for (int i = 0; i < multipart.getCount(); i++)
                    collectBody(multipart.getBodyPart(i), body);
            }
            return;
        }
        if (part.isMimeType("text/html")) {
            Object value = part.getContent();
            if (value instanceof String)
                body.html = (String) value;
            return;
        }
        if (part.isMimeType("text/plain")) {
            Object value = part.getContent();
            if (value instanceof String)
                body.plain = (String) value;
            return;
        }
        if (!isEmpty(filename) || part.getHeader("Content-ID") != null)
            body.attachments.add(part);
    }

    private static final class BoundedResponse {
        final String content;
        final long bytes;

        BoundedResponse(String content, long bytes) {
            this.content = content;
            this.bytes = bytes;
        }
    }

    private static BoundedResponse readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int length = input.read(buffer); length != -1; length = input.read(buffer)) {
            total += length;
            if (total > maxBytes)
                throw new IOException("Zoho response or attachment exceeds the local size limit");
            output.write(buffer, 0, length);
        }
        return new BoundedResponse(new String(output.toByteArray(), StandardCharsets.UTF_8), total);
    }

    private static void copy(InputStream input, OutputStream output, int maxBytes) throws IOException {
        copy(input, output, maxBytes, null);
    }

    private static void copy(InputStream input, OutputStream output, int maxBytes,
                             ZohoUploadTimeouts.Progress progress) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int length = input.read(buffer); length != -1; length = input.read(buffer)) {
            total += length;
            if (total > maxBytes)
                throw new IOException("Zoho response or attachment exceeds the local size limit");
            output.write(buffer, 0, length);
            if (progress != null)
                progress.bytesSent(total);
        }
    }

    private static boolean isEmpty(String value) {
        return (value == null || value.isEmpty());
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0)
                result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private static String encodeComponent(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte item : bytes) {
            int c = item & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~')
                encoded.append((char) c);
            else {
                encoded.append('%').append(hex[c >>> 4]).append(hex[c & 0x0f]);
            }
        }
        return encoded.toString();
    }

    private void requireAccount() {
        if (isEmpty(accountId))
            throw new IllegalStateException("Missing Zoho account ID");
    }
}
