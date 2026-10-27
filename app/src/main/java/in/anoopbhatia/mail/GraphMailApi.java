package in.anoopbhatia.mail;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import javax.mail.Part;
import javax.net.ssl.HttpsURLConnection;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/** Direct Microsoft Graph mail REST transport. No IMAP/SMTP fallback is provided. */
final class GraphMailApi {
    static final String BASE = "https://graph.microsoft.com/v1.0";
    static final int PAGE_SIZE = 10;
    static final int FOLDER_PAGE_SIZE = 100;
    static final int PURGE_PAGE_SIZE = 50;
    static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    /** Whole MIME message bound; one message can carry several attachments. */
    static final int MAX_MIME_BYTES = 50 * 1024 * 1024;
    /** Graph itself allows more per file; the shared ceiling keeps one file identical everywhere. */
    static final int MAX_ATTACHMENT_BYTES = AttachmentLimits.MAX_ATTACHMENT_BYTES;
    private static final int MAX_ERROR_BYTES = 16 * 1024;
    private static final String FILE_ATTACHMENT_TYPE = "microsoft.graph.fileAttachment";
    private static final String ITEM_ATTACHMENT_TYPE = "microsoft.graph.itemAttachment";

    interface ConnectionFactory {
        HttpsURLConnection open(URL url) throws IOException;
    }

    interface TokenRefresher {
        String refresh(String rejectedToken) throws IOException;
    }

    /** Counts every HTTP attempt, including failed responses and 401 replays, for quota pacing. */
    interface RequestListener {
        void onRequest();
    }

    interface MutationExecutor {
        MutationResult execute(URL url, String method, String accessToken, JSONObject body) throws IOException;
    }

    static final class MutationResult {
        final int status;
        final long retryAfterMs;
        final String errorCode;
        final byte[] body;

        MutationResult(int status, long retryAfterMs, String errorCode, byte[] body) {
            this.status = status;
            this.retryAfterMs = retryAfterMs;
            this.errorCode = errorCode;
            this.body = body;
        }
    }

    static final class MailFolder {
        final String id;
        final String name;
        final String parentId;
        final int childCount;
        final Integer totalItemCount;
        final Integer unreadItemCount;

        MailFolder(String id, String name, String parentId, int childCount,
                   Integer totalItemCount, Integer unreadItemCount) {
            this.id = id;
            this.name = name;
            this.parentId = parentId;
            this.childCount = childCount;
            this.totalItemCount = totalItemCount;
            this.unreadItemCount = unreadItemCount;
        }
    }

    static final class FolderStats {
        final Integer totalItemCount;
        final Integer unreadItemCount;

        FolderStats(Integer totalItemCount, Integer unreadItemCount) {
            this.totalItemCount = totalItemCount;
            this.unreadItemCount = unreadItemCount;
        }
    }

    static final class MailMessage {
        final String id;
        final long received;
        final long sent;
        final Long size;
        final boolean seen;
        final boolean flagged;
        final boolean removed;
        final String conversationId;
        final String internetMessageId;

        MailMessage(String id, long received, long sent, boolean seen, boolean flagged, boolean removed) {
            this(id, received, sent, null, seen, flagged, removed);
        }

        MailMessage(String id, long received, long sent, Long size,
                    boolean seen, boolean flagged, boolean removed) {
            this(id, received, sent, size, seen, flagged, removed, null, null);
        }

        MailMessage(String id, long received, long sent, Long size,
                    boolean seen, boolean flagged, boolean removed,
                    String conversationId, String internetMessageId) {
            this.id = id;
            this.received = received;
            this.sent = sent;
            this.size = size;
            this.seen = seen;
            this.flagged = flagged;
            this.removed = removed;
            this.conversationId = conversationId;
            this.internetMessageId = internetMessageId;
        }
    }

    static final class MessageDetails {
        final JSONObject json;
        final String id;
        final long received;
        final long sent;
        final Long size;
        final boolean seen;
        final boolean flagged;
        final boolean hasAttachments;
        final String preview;
        final String bodyType;
        final String body;
        final List<NativeApiContent.Header> headers;
        final MessageState state;

        MessageDetails(JSONObject json) throws IOException {
            this.json = json;
            state = parseMessageState(json);
            id = json.optString("id", null);
            if (isEmpty(id))
                throw new IOException("Microsoft Graph message response is missing its ID");
            received = parseGraphDate(json.optString("receivedDateTime", null));
            sent = parseGraphDate(json.optString("sentDateTime", null));
            size = optLong(json, "size");
            seen = json.optBoolean("isRead", false);
            JSONObject flag = json.optJSONObject("flag");
            flagged = (flag != null && "flagged".equalsIgnoreCase(flag.optString("flagStatus", null)));
            hasAttachments = json.optBoolean("hasAttachments", false);
            preview = json.optString("bodyPreview", null);
            JSONObject content = json.optJSONObject("body");
            bodyType = (content == null ? null : content.optString("contentType", null));
            body = (content == null ? null : content.optString("content", null));
            headers = new ArrayList<>();
            JSONArray values = json.optJSONArray("internetMessageHeaders");
            if (values != null)
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.optJSONObject(i);
                    if (value != null)
                        headers.add(new NativeApiContent.Header(
                                value.optString("name", null), value.optString("value", null)));
                }
        }
    }

    static final class MessageState {
        final String id;
        final String parentFolderId;
        final Boolean seen;
        final Boolean flagged;

        MessageState(String id, String parentFolderId, Boolean seen, Boolean flagged) {
            this.id = id;
            this.parentFolderId = parentFolderId;
            this.seen = seen;
            this.flagged = flagged;
        }
    }

    static final class Page {
        final List<MailMessage> messages;
        final String nextLink;
        final String deltaLink;
        final int status;

        Page(List<MailMessage> messages, String nextLink, String deltaLink, int status) {
            this.messages = messages;
            this.nextLink = nextLink;
            this.deltaLink = deltaLink;
            this.status = status;
        }
    }

    static final class ApiException extends IOException {
        final int status;
        final long retryAfterMs;
        final String errorCode;

        ApiException(int status, long retryAfterMs, String errorCode) {
            super("Microsoft Graph mail API HTTP " + status +
                    (isEmpty(errorCode) ? "" : " code=" + errorCode));
            this.status = status;
            this.retryAfterMs = retryAfterMs;
            this.errorCode = errorCode;
        }

        /**
         * Only an explicit throttle is a rate limit. A 5xx means the service failed the request,
         * which Gmail and Zoho already report as an ordinary failure (folder state cleared,
         * account error surfaced, exponential retry), so Graph must classify it the same way
         * instead of hiding an outage behind a silent throttle backoff.
         */
        boolean isRateLimitFailure() {
            return status == 429;
        }

        boolean isDeltaExpired() {
            return status == 410;
        }

        /** Keep 503 as a visible service failure, but honor its server-specified retry delay. */
        long getFailureRetryDelayMs(long fallbackDelayMs) {
            if (status == 503 && RetryAfter.isPresent(retryAfterMs))
                return Math.max(1000L, retryAfterMs);
            return fallbackDelayMs;
        }
    }

    private final Context context;
    private final ConnectionFactory connections;
    private final MutationExecutor mutations;
    private String accessToken;
    private TokenRefresher tokenRefresher;
    /** One folder snapshot per client: a pass reuses it instead of re-listing per message. */
    private List<MailFolder> folderSnapshot;
    private RequestListener requestListener;
    private int lastHttpStatus = -1;

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient MUTATION_CLIENT = createMutationClient();

    static OkHttpClient createMutationClient() {
        return new OkHttpClient.Builder()
            .connectTimeout(GraphTimeouts.CONNECT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(GraphTimeouts.MUTATION_RESPONSE_MILLIS, TimeUnit.MILLISECONDS)
            .writeTimeout(GraphTimeouts.MUTATION_WRITE_MILLIS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    }

    GraphMailApi(Context context, String accessToken) {
        this(context, accessToken, url -> (HttpsURLConnection) url.openConnection(),
                GraphMailApi::executeOkHttpMutation);
    }

    GraphMailApi(Context context, String accessToken, ConnectionFactory connections) {
        this(context, accessToken, connections,
                (url, method, token, body) -> executeConnectionMutation(connections, url, method, token, body));
    }

    GraphMailApi(Context context, String accessToken, ConnectionFactory connections,
                 MutationExecutor mutations) {
        if (isEmpty(accessToken))
            throw new IllegalArgumentException("Missing Microsoft Graph access token");
        this.context = (context == null ? null : context.getApplicationContext());
        this.accessToken = accessToken;
        this.connections = connections;
        this.mutations = mutations;
    }

    void setTokenRefresher(TokenRefresher refresher) {
        this.tokenRefresher = refresher;
    }

    void setRequestListener(RequestListener listener) {
        this.requestListener = listener;
    }

    private void recordRequest() {
        if (requestListener != null)
            requestListener.onRequest();
    }

    int getLastHttpStatus() {
        return lastHttpStatus;
    }

    /**
     * Whole-mailbox folder snapshot. Graph has no flat folder listing: {@code /me/mailFolders}
     * returns one level only, so discovering children used to cost one childFolders request per
     * non-leaf folder on every discovery pass. The delta endpoint walks the entire hierarchy in a
     * single paged sequence, and the snapshot is kept for this client so a pass that meets several
     * unmapped messages does not re-enumerate the mailbox for each of them.
     *
     * <p>Folders are returned parents-first. The wire order of a delta snapshot is not specified,
     * and callers create their rows while resolving a parent by remote id.</p>
     */
    List<MailFolder> listFolders() throws IOException, JSONException {
        if (folderSnapshot != null)
            return folderSnapshot;
        List<MailFolder> flattened = new ArrayList<>();
        Set<String> seenFolderIds = new HashSet<>();
        Set<String> seenLinks = new HashSet<>();
        String link = BASE + "/me/mailFolders/delta?$select=id,displayName,parentFolderId,childFolderCount,totalItemCount,unreadItemCount&$top=" + FOLDER_PAGE_SIZE;
        do {
            PageFolders page = getFolderPage(link, seenLinks);
            for (MailFolder folder : page.folders)
                if (seenFolderIds.add(folder.id))
                    flattened.add(folder);
            link = page.nextLink;
        } while (link != null);
        folderSnapshot = inHierarchyOrder(flattened);
        return folderSnapshot;
    }

    /** Parents before children, without trusting the provider's snapshot order. */
    private static List<MailFolder> inHierarchyOrder(List<MailFolder> folders) {
        Map<String, MailFolder> byId = new HashMap<>();
        for (MailFolder folder : folders)
            byId.put(folder.id, folder);
        Map<String, List<MailFolder>> children = new HashMap<>();
        ArrayDeque<MailFolder> queue = new ArrayDeque<>();
        for (MailFolder folder : folders) {
            MailFolder parent = (folder.parentId == null ? null : byId.get(folder.parentId));
            if (parent == null || parent.id.equals(folder.id))
                queue.addLast(folder);
            else
                children.computeIfAbsent(parent.id, key -> new ArrayList<>()).add(folder);
        }
        List<MailFolder> ordered = new ArrayList<>(folders.size());
        Set<String> placed = new HashSet<>();
        while (!queue.isEmpty()) {
            MailFolder folder = queue.removeFirst();
            if (!placed.add(folder.id))
                continue;
            ordered.add(folder);
            List<MailFolder> nested = children.get(folder.id);
            if (nested != null)
                queue.addAll(nested);
        }
        // A corrupt parent cycle would strand its members; keep them rather than drop folders.
        for (MailFolder folder : folders)
            if (placed.add(folder.id))
                ordered.add(folder);
        return ordered;
    }

    FolderStats getFolderStats(String folderId) throws IOException, JSONException {
        if (isEmpty(folderId))
            throw new IllegalArgumentException("Missing Graph folder ID");
        JSONObject response = getJson(BASE + "/me/mailFolders/" + segment(folderId) +
                "?$select=id,totalItemCount,unreadItemCount");
        return new FolderStats(optInteger(response, "totalItemCount"),
                optInteger(response, "unreadItemCount"));
    }

    MailFolder getUserMailFolder(String userPrincipalName, String folderId)
            throws IOException, JSONException {
        if (isEmpty(userPrincipalName) || isEmpty(folderId))
            throw new IllegalArgumentException("Missing Graph user or folder ID");
        JSONObject response = getJson(BASE + "/users/" + segment(userPrincipalName) +
                "/mailFolders/" + segment(folderId) +
                "?$select=id,displayName,parentFolderId,childFolderCount,totalItemCount,unreadItemCount");
        String id = response.optString("id", null);
        String name = response.optString("displayName", null);
        if (isEmpty(id) || isEmpty(name))
            throw new IOException("Microsoft Graph folder response is missing its identity");
        return new MailFolder(id, name, response.optString("parentFolderId", null),
                Math.max(0, response.optInt("childFolderCount", 0)),
                optInteger(response, "totalItemCount"), optInteger(response, "unreadItemCount"));
    }

    Page listMessages(String folderId) throws IOException, JSONException {
        if (isEmpty(folderId))
            throw new IllegalArgumentException("Missing Graph folder ID");
        String path = BASE + "/me/mailFolders/" + segment(folderId) +
                "/messages?$select=id,receivedDateTime,sentDateTime,isRead,flag,conversationId,internetMessageId&$orderby=receivedDateTime%20desc&$top=" + PAGE_SIZE;
        return getMessagePage(path);
    }

    Page listUserFolderMessages(String userPrincipalName, String folderId)
            throws IOException, JSONException {
        if (isEmpty(userPrincipalName) || isEmpty(folderId))
            throw new IllegalArgumentException("Missing Graph user or folder ID");
        String path = BASE + "/users/" + segment(userPrincipalName) + "/mailFolders/" +
                segment(folderId) + "/messages?$select=id,parentFolderId&$top=" + PURGE_PAGE_SIZE;
        return getPurgeMessagePage(path);
    }

    Page listUserFolderMessages(String nextLink, boolean continuation) throws IOException, JSONException {
        if (!continuation)
            throw new IllegalArgumentException("Graph purge continuation must be an opaque nextLink");
        return getPurgeMessagePage(nextLink);
    }

    Page listMessages(String nextLink, boolean continuation) throws IOException, JSONException {
        if (!continuation)
            throw new IllegalArgumentException("Graph continuation must be an opaque nextLink");
        return getMessagePage(nextLink);
    }

    Page listDelta(String folderId, long receivedSince) throws IOException, JSONException {
        if (isEmpty(folderId))
            throw new IllegalArgumentException("Missing Graph folder ID");
        String cutoff = formatGraphDate(receivedSince);
        String path = BASE + "/me/mailFolders/" + segment(folderId) +
                "/messages/delta?$select=id,receivedDateTime,sentDateTime,isRead,flag,conversationId,internetMessageId" +
                "&$orderby=receivedDateTime%20desc&$top=" + PAGE_SIZE +
                "&$filter=receivedDateTime%20ge%20" + encode(cutoff);
        return getMessagePage(path);
    }

    Page listDelta(String folderId) throws IOException, JSONException {
        if (isEmpty(folderId))
            throw new IllegalArgumentException("Missing Graph folder ID");
        String path = BASE + "/me/mailFolders/" + segment(folderId) +
                "/messages/delta?$select=id,receivedDateTime,sentDateTime,isRead,flag,conversationId,internetMessageId" +
                "&$orderby=receivedDateTime%20desc&$top=" + PAGE_SIZE;
        return getMessagePage(path);
    }

    Page listDelta(String link, boolean continuation) throws IOException, JSONException {
        if (!continuation)
            throw new IllegalArgumentException("Graph continuation must be an opaque nextLink or deltaLink");
        return getMessagePage(link);
    }

    MessageDetails getMessage(String messageId, boolean includeBody)
            throws IOException, JSONException {
        if (isEmpty(messageId))
            throw new IllegalArgumentException("Missing Graph message ID");
        String select = "id,receivedDateTime,sentDateTime,isRead,flag,hasAttachments,bodyPreview," +
                "parentFolderId,internetMessageHeaders" + (includeBody ? ",body" : "");
        JSONObject response = getJson(BASE + "/me/messages/" + segment(messageId) + "?$select=" + select);
        return new MessageDetails(response);
    }

    List<NativeApiContent.Attachment> listMessageAttachments(String messageId)
            throws IOException, JSONException {
        if (isEmpty(messageId))
            throw new IllegalArgumentException("Missing Graph message ID");
        List<NativeApiContent.Attachment> result = new ArrayList<>();
        String link = BASE + "/me/messages/" + segment(messageId) +
                "/attachments?$select=id,name,contentType,size,isInline";
        Set<String> seenLinks = new HashSet<>();
        while (link != null) {
            if (!seenLinks.add(link))
                throw new IOException("Microsoft Graph returned a repeated attachment continuation URL");
            JSONObject response = getJson(link);
            JSONArray values = response.optJSONArray("value");
            if (values == null)
                throw new IOException("Microsoft Graph attachment response did not contain a value array");
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.optJSONObject(i);
                if (item == null)
                    continue;
                String id = item.optString("id", null);
                if (isEmpty(id))
                    continue;
                String type = item.optString("@odata.type", null);
                boolean inline = item.optBoolean("isInline", false);
                Long size = optLong(item, "size");
                String contentId = item.optString("contentId", null);
                if (inline && isFileAttachmentType(type) && isEmpty(contentId))
                    contentId = getFileAttachmentContentId(messageId, id);
                result.add(new NativeApiContent.Attachment(id, null, type,
                        item.optString("name", null), item.optString("contentType", null),
                        (inline ? Part.INLINE : Part.ATTACHMENT), contentId, size, null, inline));
            }
            link = safeLink(response.optString("@odata.nextLink", null));
        }
        return result;
    }

    private String getFileAttachmentContentId(String messageId, String attachmentId)
            throws IOException, JSONException {
        String path = BASE + "/me/messages/" + segment(messageId) + "/attachments/" +
                segment(attachmentId) + "/" + FILE_ATTACHMENT_TYPE + "?$select=contentId";
        JSONObject response = getJson(path);
        return response.optString("contentId", null);
    }

    static boolean isFileAttachmentType(String type) {
        return FILE_ATTACHMENT_TYPE.equalsIgnoreCase(normalizeAttachmentType(type));
    }

    static boolean isItemAttachmentType(String type) {
        return ITEM_ATTACHMENT_TYPE.equalsIgnoreCase(normalizeAttachmentType(type));
    }

    static boolean isRawContentAttachmentType(String type) {
        return (isFileAttachmentType(type) || isItemAttachmentType(type));
    }

    private static String normalizeAttachmentType(String type) {
        if (isEmpty(type))
            return null;
        String value = type.trim();
        if (value.startsWith("#"))
            value = value.substring(1);
        if (value.startsWith(".graph."))
            value = "microsoft" + value;
        return value;
    }

    void downloadAttachment(String messageId, String attachmentId, File destination)
            throws IOException {
        if (isEmpty(messageId) || isEmpty(attachmentId) || destination == null)
            throw new IllegalArgumentException("Missing Graph attachment identity or destination");
        requestToFile(BASE + "/me/messages/" + segment(messageId) + "/attachments/" +
                segment(attachmentId) + "/$value", "application/octet-stream",
                MAX_ATTACHMENT_BYTES, destination, true);
    }

    void downloadOriginalMessage(String messageId, File destination) throws IOException {
        if (isEmpty(messageId))
            throw new IllegalArgumentException("Missing Graph message ID");
        if (destination == null)
            throw new IllegalArgumentException("Missing Graph MIME destination");
        requestToFile(BASE + "/me/messages/" + segment(messageId) + "/$value",
                "text/plain", MAX_MIME_BYTES, destination, false);
    }

    MessageState setMessageRead(String messageId, boolean seen) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("isRead", seen);
        return updatedState(requestMutation(BASE + "/me/messages/" + segment(messageId), "PATCH", body));
    }

    MessageState setMessageFlagged(String messageId, boolean flagged) throws IOException, JSONException {
        JSONObject flag = new JSONObject().put("flagStatus", flagged ? "flagged" : "notFlagged");
        JSONObject body = new JSONObject().put("flag", flag);
        return updatedState(requestMutation(BASE + "/me/messages/" + segment(messageId), "PATCH", body));
    }

    private static MessageState updatedState(Response response) {
        if (response.body.length == 0)
            return null;
        try {
            return parseMessageState(new JSONObject(new String(response.body, StandardCharsets.UTF_8)));
        } catch (JSONException ex) {
            // A malformed representation does not undo a successful PATCH. Do not replay it.
            return null;
        }
    }

    MessageState moveMessage(String messageId, String destinationFolderId) throws IOException, JSONException {
        if (isEmpty(destinationFolderId))
            throw new IllegalArgumentException("Missing Graph destination folder ID");
        JSONObject body = new JSONObject().put("destinationId", destinationFolderId);
        Response response = requestMutation(BASE + "/me/messages/" + segment(messageId) + "/move", "POST", body);
        if (response.body.length == 0)
            return null;
        try {
            return parseMessageState(new JSONObject(new String(response.body, StandardCharsets.UTF_8)));
        } catch (JSONException ex) {
            return null; // The POST succeeded; invalidate local identity and reconcile by folder delta.
        }
    }

    MessageState moveMessageToDeletedItems(String messageId) throws IOException, JSONException {
        return moveMessage(messageId, "deleteditems");
    }

    void deleteMessageFromFolder(String folderId, String messageId) throws IOException, JSONException {
        if (isEmpty(folderId) || isEmpty(messageId))
            throw new IllegalArgumentException("Missing Graph folder or message ID");
        requestMutation(BASE + "/me/mailFolders/" + segment(folderId) + "/messages/" +
                segment(messageId), "DELETE", null);
    }

    void permanentDeleteMessage(String userPrincipalName, String folderId, String messageId)
            throws IOException, JSONException {
        if (isEmpty(userPrincipalName) || isEmpty(folderId) || isEmpty(messageId))
            throw new IllegalArgumentException("Missing Graph user, folder, or message ID");
        requestMutation(BASE + "/users/" + segment(userPrincipalName) +
                "/mailFolders/" + segment(folderId) + "/messages/" + segment(messageId) +
                "/permanentDelete", "POST", null);
        if (lastHttpStatus != HttpsURLConnection.HTTP_NO_CONTENT)
            throw new IOException("Microsoft Graph permanentDelete returned unexpected HTTP " +
                    lastHttpStatus + "; verify remote state before retrying");
    }

    MessageState getMessageState(String messageId) throws IOException, JSONException {
        if (isEmpty(messageId))
            throw new IllegalArgumentException("Missing Graph message ID");
        JSONObject response = getJson(BASE + "/me/messages/" + segment(messageId) +
                "?$select=id,parentFolderId,isRead,flag");
        return parseMessageState(response);
    }

    MessageState getUserMessageState(String userPrincipalName, String messageId)
            throws IOException, JSONException {
        if (isEmpty(userPrincipalName) || isEmpty(messageId))
            throw new IllegalArgumentException("Missing Graph user or message ID");
        JSONObject response = getJson(BASE + "/users/" + segment(userPrincipalName) +
                "/messages/" + segment(messageId) + "?$select=id,parentFolderId,isRead,flag");
        return parseMessageState(response);
    }

    MessageState findMessageStateByInternetMessageId(String internetMessageId)
            throws IOException, JSONException {
        if (isEmpty(internetMessageId))
            return null;
        String escaped = internetMessageId.replace("'", "''");
        JSONObject response = getJson(BASE + "/me/messages?$select=id,parentFolderId,isRead,flag" +
                "&$filter=internetMessageId%20eq%20%27" + encode(escaped) + "%27&$top=2");
        JSONArray values = response.optJSONArray("value");
        if (values == null)
            throw new IOException("Microsoft Graph message lookup returned no value array");
        if (values.length() != 1 || !isEmpty(response.optString("@odata.nextLink", null)))
            return null;
        JSONObject item = values.optJSONObject(0);
        return (item == null ? null : parseMessageState(item));
    }

    private static MessageState parseMessageState(JSONObject response) {
        JSONObject flag = response.optJSONObject("flag");
        String flagStatus = (flag == null ? null : flag.optString("flagStatus", null));
        Boolean seen = (response.has("isRead") && !response.isNull("isRead")
                ? response.optBoolean("isRead") : null);
        Boolean flagged = (flagStatus == null ? null : Boolean.valueOf("flagged".equalsIgnoreCase(flagStatus)));
        return new MessageState(response.optString("id", null),
                response.optString("parentFolderId", null), seen, flagged);
    }

    private static final class PageFolders {
        final List<MailFolder> folders;
        final String nextLink;

        PageFolders(List<MailFolder> folders, String nextLink) {
            this.folders = folders;
            this.nextLink = nextLink;
        }
    }

    private PageFolders getFolderPage(String link, Set<String> seenLinks) throws IOException, JSONException {
        if (!seenLinks.add(link))
            throw new IOException("Microsoft Graph returned a repeated folder continuation link");
        JSONObject response = getJson(link);
        JSONArray values = response.optJSONArray("value");
        if (values == null)
            throw new IOException("Microsoft Graph folder response did not contain a value array");
        List<MailFolder> folders = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null)
                continue;
            String id = item.optString("id", null);
            String name = item.optString("displayName", null);
            if (isEmpty(id) || isEmpty(name))
                continue;
            folders.add(new MailFolder(id, name, item.optString("parentFolderId", null),
                    Math.max(0, item.optInt("childFolderCount", 0)),
                    optInteger(item, "totalItemCount"), optInteger(item, "unreadItemCount")));
        }
        return new PageFolders(folders, safeLink(response.optString("@odata.nextLink", null)));
    }

    private Page getMessagePage(String link) throws IOException, JSONException {
        JSONObject response = getJson(link);
        JSONArray values = response.optJSONArray("value");
        if (values == null)
            throw new IOException("Microsoft Graph message response did not contain a value array");
        List<MailMessage> messages = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null)
                continue;
            String id = item.optString("id", null);
            if (isEmpty(id))
                continue;
            JSONObject removed = item.optJSONObject("@removed");
            JSONObject flag = item.optJSONObject("flag");
            String flagStatus = (flag == null ? null : flag.optString("flagStatus", null));
            messages.add(new MailMessage(id,
                    parseGraphDate(item.optString("receivedDateTime", null)),
                    parseGraphDate(item.optString("sentDateTime", null)),
                    optLong(item, "size"),
                    item.optBoolean("isRead", false),
                    "flagged".equalsIgnoreCase(flagStatus),
                    removed != null,
                    item.optString("conversationId", null),
                    item.optString("internetMessageId", null)));
        }
        return new Page(messages,
                safeLink(response.optString("@odata.nextLink", null)),
                safeLink(response.optString("@odata.deltaLink", null)),
                lastHttpStatus);
    }

    private Page getPurgeMessagePage(String link) throws IOException, JSONException {
        JSONObject response = getJson(link);
        JSONArray values = response.optJSONArray("value");
        if (values == null)
            throw new IOException("Microsoft Graph purge response did not contain a value array");
        List<MailMessage> messages = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            String id = (item == null ? null : item.optString("id", null));
            if (isEmpty(id))
                throw new IOException("Microsoft Graph purge response contained a message without an ID");
            messages.add(new MailMessage(id,
                    parseGraphDate(item.optString("receivedDateTime", null)),
                    parseGraphDate(item.optString("sentDateTime", null)),
                    optLong(item, "size"),
                    item.optBoolean("isRead", false), false, false));
        }
        return new Page(messages,
                safeLink(response.optString("@odata.nextLink", null)),
                safeLink(response.optString("@odata.deltaLink", null)), lastHttpStatus);
    }

    private JSONObject getJson(String link) throws IOException, JSONException {
        Response response = request(link, "application/json", MAX_JSON_BYTES);
        return new JSONObject(new String(response.body, StandardCharsets.UTF_8));
    }

    private static final class Response {
        final byte[] body;

        Response(byte[] body) {
            this.body = body;
        }
    }

    private Response request(String link, String accept, int maxBytes) throws IOException {
        String requestUrl = safeLink(link);
        if (requestUrl == null)
            throw new IOException("Missing Microsoft Graph URL");
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpsURLConnection connection = null;
            int responseStatus = -1;
            try {
                recordRequest();
                connection = connections.open(new URL(requestUrl));
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(GraphTimeouts.CONNECT_MILLIS);
                connection.setReadTimeout(GraphTimeouts.GET_RESPONSE_MILLIS);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", accept);
                connection.setRequestProperty("Authorization", "Bearer " + accessToken);
                if (context != null)
                    ConnectionHelper.setUserAgent(context, connection);
                lastHttpStatus = connection.getResponseCode();
                responseStatus = lastHttpStatus;
                if (lastHttpStatus == HttpsURLConnection.HTTP_UNAUTHORIZED && attempt == 0 && tokenRefresher != null) {
                    NativeApiDiagnostics.logGraph(context, "GET", requestUrl, lastHttpStatus, null);
                    String rejected = accessToken;
                    String refreshed = tokenRefresher.refresh(rejected);
                    if (isEmpty(refreshed) || refreshed.equals(rejected))
                        throw new IOException("Microsoft Graph requires account reauthorization");
                    accessToken = refreshed;
                    continue;
                }
                if (lastHttpStatus < 200 || lastHttpStatus >= 300) {
                    byte[] errorBody = readErrorBody(connection);
                    NativeApiDiagnostics.logGraph(context, "GET", requestUrl, lastHttpStatus, errorBody);
                    throw new ApiException(lastHttpStatus, getRetryAfter(connection), readErrorCode(errorBody));
                }
                NativeApiDiagnostics.logGraph(context, "GET", requestUrl, lastHttpStatus, null);
                try (InputStream input = connection.getInputStream()) {
                    return new Response(readBounded(input, maxBytes));
                }
            } catch (IOException ex) {
                if (responseStatus < 0)
                    NativeApiDiagnostics.logGraph(context, "GET", requestUrl, -1, null);
                // A GET can be replayed; a lost socket should not cost the whole pass.
                if (TransientGetRetry.shouldReplay("GET", attempt, ex) &&
                        TransientGetRetry.pause(attempt))
                    continue;
                throw ex;
            } finally {
                if (connection != null)
                    connection.disconnect();
            }
        }
        throw new IOException("Microsoft Graph request retry exhausted");
    }

    private Response requestMutation(String link, String method, JSONObject body)
            throws IOException, JSONException {
        String requestUrl = safeLink(link);
        if (requestUrl == null)
            throw new IOException("Missing Microsoft Graph URL");
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean responseReceived = false;
            try {
                recordRequest();
                MutationResult result = mutations.execute(new URL(requestUrl), method, accessToken, body);
                responseReceived = true;
                lastHttpStatus = result.status;
                NativeApiDiagnostics.logGraph(context, method, requestUrl, lastHttpStatus,
                        (lastHttpStatus < 200 || lastHttpStatus >= 300 ? result.body : null));
                if (lastHttpStatus == HttpsURLConnection.HTTP_UNAUTHORIZED && attempt == 0 &&
                        tokenRefresher != null) {
                    String rejected = accessToken;
                    String refreshed = tokenRefresher.refresh(rejected);
                    if (isEmpty(refreshed) || refreshed.equals(rejected))
                        throw new IOException("Microsoft Graph requires account reauthorization");
                    accessToken = refreshed;
                    continue;
                }
                if (lastHttpStatus < 200 || lastHttpStatus >= 300)
                    throw new ApiException(lastHttpStatus, result.retryAfterMs, result.errorCode);
                return new Response(result.body == null ? new byte[0] : result.body);
            } catch (IOException ex) {
                if (!responseReceived)
                    NativeApiDiagnostics.logGraph(context, method, requestUrl, -1, null);
                throw ex;
            }
        }
        throw new IOException("Microsoft Graph mutation authentication retry exhausted");
    }

    private static MutationResult executeOkHttpMutation(URL url, String method,
                                                          String accessToken, JSONObject body)
            throws IOException {
        Request request = buildMutationRequest(url, method, accessToken, body);
        try (okhttp3.Response response = MUTATION_CLIENT.newCall(request).execute()) {
            int status = response.code();
            byte[] responseBytes = new byte[0];
            ResponseBody responseBody = response.body();
            if (responseBody != null) {
                try (InputStream input = responseBody.byteStream()) {
                    responseBytes = readBounded(input,
                            (status >= 200 && status < 300 ? MAX_JSON_BYTES : MAX_ERROR_BYTES));
                } catch (IOException ex) {
                    if (status >= 200 && status < 300)
                        throw ex;
                    // Keep the definite HTTP failure even if its diagnostic body is too large.
                }
            }
            String errorCode = (status >= 200 && status < 300 ? null : readErrorCode(responseBytes));
            return new MutationResult(status,
                    RetryAfter.parseMillis(response.header("Retry-After"),
                            System.currentTimeMillis()),
                    errorCode, responseBytes);
        }
    }

    static Request buildMutationRequest(URL url, String method, String accessToken, JSONObject body) {
        RequestBody requestBody = (body == null
                ? ("POST".equals(method) ? RequestBody.create(new byte[0], JSON_MEDIA_TYPE) : null)
                : RequestBody.create(body.toString(), JSON_MEDIA_TYPE));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken);
        if ("POST".equals(method))
            builder.header("Prefer", "return=minimal");
        return builder.method(method, requestBody).build();
    }

    private static MutationResult executeConnectionMutation(ConnectionFactory connections, URL url, String method,
                                                              String accessToken, JSONObject body)
            throws IOException {
        HttpsURLConnection connection = null;
        try {
            connection = connections.open(url);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(GraphTimeouts.CONNECT_MILLIS);
            connection.setReadTimeout(GraphTimeouts.MUTATION_RESPONSE_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            if ("POST".equals(method))
                connection.setRequestProperty("Prefer", "return=minimal");
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            byte[] responseBody = new byte[0];
            if (status >= 200 && status < 300 && status != HttpsURLConnection.HTTP_NO_CONTENT) {
                try (InputStream input = connection.getInputStream()) {
                    responseBody = readBounded(input, MAX_JSON_BYTES);
                }
            } else if (status < 200 || status >= 300) {
                responseBody = readErrorBody(connection);
                String errorCode = readErrorCode(responseBody);
                return new MutationResult(status, getRetryAfter(connection), errorCode, responseBody);
            }
            return new MutationResult(status, getRetryAfter(connection), null, responseBody);
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    private void requestToFile(String link, String accept, int maxBytes, File destination,
                               boolean requireNonEmpty) throws IOException {
        String requestUrl = safeLink(link);
        if (requestUrl == null)
            throw new IOException("Missing Microsoft Graph URL");
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpsURLConnection connection = null;
            int responseStatus = -1;
            try {
                recordRequest();
                connection = connections.open(new URL(requestUrl));
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(GraphTimeouts.CONNECT_MILLIS);
                connection.setReadTimeout(GraphTimeouts.GET_RESPONSE_MILLIS);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", accept);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Authorization", "Bearer " + accessToken);
                if (context != null)
                    ConnectionHelper.setUserAgent(context, connection);
                lastHttpStatus = connection.getResponseCode();
                responseStatus = lastHttpStatus;
                if (lastHttpStatus == HttpsURLConnection.HTTP_UNAUTHORIZED && attempt == 0 && tokenRefresher != null) {
                    NativeApiDiagnostics.logGraph(context, "GET", requestUrl, lastHttpStatus, null);
                    String rejected = accessToken;
                    String refreshed = tokenRefresher.refresh(rejected);
                    if (isEmpty(refreshed) || refreshed.equals(rejected))
                        throw new IOException("Microsoft Graph requires account reauthorization");
                    accessToken = refreshed;
                    continue;
                }
                if (lastHttpStatus < 200 || lastHttpStatus >= 300) {
                    byte[] errorBody = readErrorBody(connection);
                    NativeApiDiagnostics.logGraph(context, "GET", requestUrl, lastHttpStatus, errorBody);
                    throw new ApiException(lastHttpStatus, getRetryAfter(connection), readErrorCode(errorBody));
                }
                NativeApiDiagnostics.logGraph(context, "GET", requestUrl, lastHttpStatus, null);
                long contentLength = connection.getContentLength();
                String contentEncoding = connection.getHeaderField("Content-Encoding");
                if (!isEmpty(contentEncoding) && !"identity".equalsIgnoreCase(contentEncoding.trim()))
                    contentLength = -1;
                try (InputStream input = connection.getInputStream();
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                    long copied = copyBounded(input, output, maxBytes);
                    if (contentLength >= 0 && copied != contentLength)
                        throw new IOException("Microsoft Graph response length did not match HTTP Content-Length");
                    if (requireNonEmpty && copied == 0)
                        throw new IOException("Microsoft Graph returned an empty attachment response");
                }
                return;
            } catch (IOException ex) {
                if (responseStatus < 0)
                    NativeApiDiagnostics.logGraph(context, "GET", requestUrl, -1, null);
                if (destination.exists() && !destination.delete())
                    Log.w("Graph MIME temporary-file cleanup failed");
                if (TransientGetRetry.shouldReplay("GET", attempt, ex) &&
                        TransientGetRetry.pause(attempt))
                    continue;
                throw ex;
            } finally {
                if (connection != null)
                    connection.disconnect();
            }
        }
        throw new IOException("Microsoft Graph request retry exhausted");
    }

    private static String safeLink(String link) throws IOException {
        if (isEmpty(link))
            return null;
        try {
            URL url = new URL(link);
            if (!"https".equalsIgnoreCase(url.getProtocol()) ||
                    !"graph.microsoft.com".equalsIgnoreCase(url.getHost()) ||
                    (url.getPort() != -1 && url.getPort() != 443) ||
                    url.getPath() == null || !url.getPath().startsWith("/v1.0/"))
                throw new IOException("Microsoft Graph returned an unexpected continuation origin");
            return url.toExternalForm();
        } catch (IOException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IOException("Microsoft Graph returned an invalid continuation URL");
        }
    }

    private static long getRetryAfter(HttpsURLConnection connection) {
        return RetryAfter.parseMillis(connection.getHeaderField("Retry-After"),
                System.currentTimeMillis());
    }

    private static byte[] readErrorBody(HttpsURLConnection connection) {
        try (InputStream input = connection.getErrorStream()) {
            if (input == null)
                return new byte[0];
            return readBounded(input, MAX_ERROR_BYTES);
        } catch (Throwable ignored) {
            // Preserve the HTTP status; do not log arbitrary Graph error bodies.
        }
        return new byte[0];
    }

    private static String readErrorCode(byte[] bytes) {
        try {
            String body = new String(bytes, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            String code = (error == null ? null : error.optString("code", null));
            if (code != null && code.length() <= 100 && code.matches("[A-Za-z0-9._-]+"))
                return code;
        } catch (Throwable ignored) {
            // Preserve the HTTP status; do not log arbitrary Graph error bodies.
        }
        return null;
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int length = input.read(buffer); length != -1; length = input.read(buffer)) {
            total += length;
            if (total > maxBytes)
                throw new IOException("Microsoft Graph response exceeds the local size limit");
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    private static long copyBounded(InputStream input, OutputStream output, int maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int length = input.read(buffer); length != -1; length = input.read(buffer)) {
            total += length;
            if (total > maxBytes)
                throw new IOException("Microsoft Graph response exceeds the local size limit");
            output.write(buffer, 0, length);
        }
        return total;
    }

    static String segment(String value) {
        return encode(value).replace("+", "%20");
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    private static Integer optInteger(JSONObject object, String name) {
        return (object.has(name) && !object.isNull(name)
                ? Integer.valueOf(object.optInt(name)) : null);
    }
    private static Long optLong(JSONObject object, String name) {
        return (object.has(name) && !object.isNull(name)
                ? Long.valueOf(object.optLong(name)) : null);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to encode Microsoft Graph query");
        }
    }

    private static String formatGraphDate(long value) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(Math.max(0, value)));
    }

    static long parseGraphDate(String value) throws IOException {
        if (isEmpty(value))
            return 0;
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (java.time.DateTimeException | ArithmeticException ex) {
            throw new IOException("Microsoft Graph returned an invalid message date/time", ex);
        }
    }
}
