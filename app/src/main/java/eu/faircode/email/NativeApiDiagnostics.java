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

import org.json.JSONObject;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Privacy-limited Protocol entries for the native REST API paths under investigation. */
final class NativeApiDiagnostics {
    private static final int MAX_ERROR_MESSAGE_LENGTH = 120;
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b");
    private static final Pattern URL_OR_HOST = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\\\"']+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(?:bearer|basic)\\s+[a-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("\\beyJ[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}(?:\\.[a-zA-Z0-9_-]*)?\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern OPAQUE_ID = Pattern.compile("\\b[a-zA-Z0-9_-]{16,}\\b");
    private static final Pattern SAFE_GRAPH_ERROR_CODE = Pattern.compile(
            "(?:BadRequest|Unauthorized|Forbidden|NotFound|Conflict|TooManyRequests|" +
                    "[A-Z][a-z]+(?:[A-Z][a-zA-Z]+)+|[A-Z][a-z]+(?:_[A-Z][a-zA-Z]+)+)");
    private static final Pattern CONTENT_TAIL = Pattern.compile("(?is)\\b(?:message|body|content|subject|snippet|preview)\\s*(?:text|html)?\\s*[:=].*$");
    private static final Pattern QUOTED = Pattern.compile("(?s)(\"[^\"]*\"|'[^']*')");
    private static final Pattern WORD_OR_PUNCTUATION = Pattern.compile("[a-zA-Z][a-zA-Z'-]*|[^\\p{L}\\p{N}\\s]");
    private static final Set<String> SAFE_QUERY_NAMES = new HashSet<>(Arrays.asList(
            "$select", "$expand", "$filter", "$orderby", "$top", "$skip", "$skiptoken",
            "$count", "$search", "$format", "$deltatoken", "deltatoken"));
    private static final Set<String> SAFE_ERROR_WORDS = new HashSet<>(Arrays.asList(
            "the", "a", "an", "this", "that", "is", "are", "was", "were", "be", "been", "being",
            "has", "have", "had", "do", "does", "did", "may", "might", "can", "cannot", "could",
            "would", "should", "will", "not", "no", "and", "or", "but", "if", "then", "to", "of",
            "in", "on", "for", "from", "by", "with", "as", "at", "after", "before", "during", "via",
            "request", "requests", "requested", "method", "parameter", "parameters", "query", "filter",
            "select", "orderby", "top", "skip", "page", "paging", "continuation", "delta", "folder",
            "folders", "message", "messages", "attachment", "attachments", "mailbox", "user", "users",
            "resource", "resources", "identifier", "identifiers", "missing", "invalid", "malformed",
            "unsupported", "supported", "unexpected", "unavailable", "found", "exists", "already",
            "deleted", "expired", "denied", "forbidden", "unauthorized", "authentication", "authorization",
            "access", "permission", "permissions", "policy", "limit", "maximum", "minimum", "exceeded",
            "too", "large", "small", "greater", "less", "than", "equal", "required", "optional", "value",
            "values", "property", "properties", "field", "fields", "type", "format", "version", "endpoint",
            "operation", "service", "server", "temporarily", "internal", "processing", "rejected", "failed",
            "failure", "conflict", "current", "state", "status", "error", "errors", "empty", "must", "only",
            "read", "write", "update", "create", "remove", "retrieve", "fetch", "return", "returned",
            "parsing", "parse", "notfound", "badrequest", "invalidrequest", "unauthenticated", "unknown"));

    private NativeApiDiagnostics() {
    }

    static void logGraph(Context context, String method, String url, int status, byte[] errorBody) {
        if (context != null)
            EntityLog.logProtocol(context, formatGraph(method, url, status, errorBody,
                    ApiMutationLog.currentTraceId()));
    }

    static String formatGraph(String method, String url, int status, byte[] errorBody) {
        return formatGraph(method, url, status, errorBody, null);
    }

    static String formatGraph(String method, String url, int status, byte[] errorBody, String traceId) {
        String safeMethod = (method != null && method.matches("[A-Za-z]{1,12}")
                ? method.toUpperCase(Locale.ROOT) : "UNKNOWN");
        StringBuilder result = new StringBuilder("Graph method=").append(safeMethod)
                .append(" route=").append(sanitizeGraphRoute(url))
                .append(" query=").append(queryNames(url))
                .append(" status=").append(status);
        GraphError error = parseGraphError(errorBody);
        if (error.code != null)
            result.append(" error.code=").append(error.code);
        if (error.innerCode != null)
            result.append(" innerError.code=").append(error.innerCode);
        if (error.message != null)
            result.append(" error.message=").append(error.message);
        String safeTrace = ApiMutationLog.safeTraceId(traceId);
        if (safeTrace != null)
            result.append(" trace=").append(safeTrace);
        return result.toString();
    }

    static String sanitizeGraphRoute(String url) {
        try {
            URL parsed = new URL(url);
            String path = parsed.getPath();
            if (path == null || !path.startsWith("/v1.0/"))
                return "/{route}";
            String[] segments = path.substring(1).split("/");
            StringBuilder safe = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                if (segment.isEmpty())
                    continue;
                if (safe.length() > 0)
                    safe.append('/');
                String previous = (i == 0 ? "" : segments[i - 1]);
                if (i == 0 && "v1.0".equals(segment))
                    safe.append("v1.0");
                else if (i == 1 && ("me".equals(segment) || "users".equals(segment)))
                    safe.append(segment);
                else if ("users".equals(previous) || "mailFolders".equals(previous) ||
                        "attachments".equals(previous) ||
                        ("messages".equals(previous) && !"delta".equals(segment)))
                    safe.append("{id}");
                else if (isSafeRouteSegment(segment))
                    safe.append(segment);
                else
                    safe.append("{id}");
            }
            return "/" + safe;
        } catch (Throwable ignored) {
            return "/{route}";
        }
    }

    private static boolean isSafeRouteSegment(String segment) {
        return ("me".equals(segment) || "users".equals(segment) || "mailFolders".equals(segment) ||
                "childFolders".equals(segment) || "messages".equals(segment) || "delta".equals(segment) ||
                "attachments".equals(segment) || "$value".equals(segment) || "move".equals(segment) ||
                "permanentDelete".equals(segment) || "sendMail".equals(segment) || "$batch".equals(segment) ||
                "subscriptions".equals(segment) || "$count".equals(segment) || "extensions".equals(segment));
    }

    private static String queryNames(String url) {
        try {
            URL parsed = new URL(url);
            String query = parsed.getQuery();
            if (query == null || query.isEmpty())
                return "[]";
            List<String> names = new ArrayList<>();
            for (String parameter : query.split("&")) {
                int equals = parameter.indexOf('=');
                String name = (equals < 0 ? parameter : parameter.substring(0, equals));
                if (SAFE_QUERY_NAMES.contains(name.toLowerCase(Locale.ROOT)))
                    names.add(name);
                else if (!name.isEmpty())
                    names.add("{param}");
            }
            return names.toString();
        } catch (Throwable ignored) {
            return "[]";
        }
    }

    private static GraphError parseGraphError(byte[] bytes) {
        if (bytes == null || bytes.length == 0)
            return GraphError.EMPTY;
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONObject error = root.optJSONObject("error");
            if (error == null)
                return GraphError.EMPTY;
            String code = safeCode(error.optString("code", null));
            JSONObject inner = error.optJSONObject("innerError");
            if (inner == null)
                inner = error.optJSONObject("innererror");
            String innerCode = null;
            for (int depth = 0; inner != null && depth < 4; depth++) {
                innerCode = safeCode(inner.optString("code", null));
                if (innerCode != null)
                    break;
                JSONObject nested = inner.optJSONObject("innerError");
                if (nested == null)
                    nested = inner.optJSONObject("innererror");
                inner = nested;
            }
            String message = sanitizeErrorMessage(error.optString("message", null));
            return new GraphError(code, innerCode, message);
        } catch (Throwable ignored) {
            return GraphError.EMPTY;
        }
    }

    private static String safeCode(String value) {
        if (value == null || value.length() == 0 || value.length() > 64 ||
                !SAFE_GRAPH_ERROR_CODE.matcher(value).matches())
            return null;
        return value;
    }

    static String sanitizeErrorMessage(String value) {
        if (value == null || value.isEmpty())
            return null;
        String safe = value.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ");
        safe = CONTENT_TAIL.matcher(safe).replaceFirst(" [redacted]");
        safe = URL_OR_HOST.matcher(safe).replaceAll(" [redacted]");
        safe = BEARER.matcher(safe).replaceAll(" [redacted]");
        safe = JWT.matcher(safe).replaceAll(" [redacted]");
        safe = EMAIL.matcher(safe).replaceAll(" [redacted]");
        safe = IPV4.matcher(safe).replaceAll(" [redacted]");
        safe = UUID.matcher(safe).replaceAll(" [redacted]");
        safe = OPAQUE_ID.matcher(safe).replaceAll(" [redacted]");
        safe = QUOTED.matcher(safe).replaceAll(" [redacted]");

        StringBuilder filtered = new StringBuilder();
        Matcher matcher = WORD_OR_PUNCTUATION.matcher(safe);
        while (matcher.find()) {
            if (filtered.length() > 0 && matcher.start() > 0 &&
                    Character.isWhitespace(safe.charAt(matcher.start() - 1)) &&
                    filtered.charAt(filtered.length() - 1) != ' ')
                filtered.append(' ');
            String token = matcher.group();
            if (token.matches("[A-Za-z][A-Za-z'-]*")) {
                if (SAFE_ERROR_WORDS.contains(token.toLowerCase(Locale.ROOT)))
                    filtered.append(token);
                else
                    filtered.append("[redacted]");
            } else if (".".equals(token) || ",".equals(token) || ":".equals(token) ||
                    ";".equals(token) || "-".equals(token))
                filtered.append(token);
        }
        safe = filtered.toString().replaceAll("(?:\\[redacted\\]\\s*){2,}", "[redacted] ")
                .replaceAll("\\s+([.,:;])", "$1").replaceAll("\\s+", " ").trim();
        if (safe.isEmpty())
            return null;
        if (safe.length() > MAX_ERROR_MESSAGE_LENGTH)
            safe = safe.substring(0, MAX_ERROR_MESSAGE_LENGTH).trim();
        return safe;
    }

    static void logBody(Context context, String provider, boolean success,
                        long sourceLength, long renderedChars, boolean cacheReused) {
        if (context != null)
            EntityLog.logProtocol(context, formatBody(provider, success, sourceLength,
                    renderedChars, cacheReused));
    }

    static String formatBody(String provider, boolean success, long sourceLength,
                             long renderedChars, boolean cacheReused) {
        return "Native provider=" + safeProvider(provider) + " operation=body event=complete success=" + success +
                " source_length=" + sourceLength + " rendered_chars=" + renderedChars +
                " cache_reused=" + cacheReused;
    }

    static void logGmailMime(Context context, String stage, GmailApi.ReceivedMessage message) {
        if (context != null && message != null)
            EntityLog.logProtocol(context, formatGmailMime(stage, message.bodyIncluded,
                    message.bodyParts, message.incompleteBodyParts, message.fetchedBodyParts,
                    message.attachmentParts, message.attachmentIdParts,
                    message.inlineDataAttachmentParts));
    }

    static String formatGmailMime(String stage, boolean bodyComplete, int bodyParts,
                                  int incompleteBodyParts, int fetchedBodyParts,
                                  int attachmentParts, int attachmentIdParts,
                                  int inlineDataAttachmentParts) {
        String safeStage = ("import".equals(stage) || "body_open".equals(stage)
                ? stage : "unknown");
        return "Native provider=gmail operation=mime event=body_parse stage=" + safeStage +
                " body_complete=" + bodyComplete +
                " text_parts=" + Math.max(0, bodyParts) +
                " incomplete_text_parts=" + Math.max(0, incompleteBodyParts) +
                " fetched_text_parts=" + Math.max(0, fetchedBodyParts) +
                " attachment_parts=" + Math.max(0, attachmentParts) +
                " attachment_id_parts=" + Math.max(0, attachmentIdParts) +
                " inline_data_attachment_parts=" + Math.max(0, inlineDataAttachmentParts);
    }

    static void logGmailAttachmentImport(Context context, int parsedParts, int mimeCandidates,
                                         int remoteCandidates, int fallbackRows, int rowsInserted) {
        if (context != null)
            EntityLog.logProtocol(context, formatGmailAttachmentImport(parsedParts, mimeCandidates,
                    remoteCandidates, fallbackRows, rowsInserted));
    }

    static String formatGmailAttachmentImport(int parsedParts, int mimeCandidates,
                                              int remoteCandidates, int fallbackRows,
                                              int rowsInserted) {
        return "Native provider=gmail operation=attachment_import event=complete" +
                " parsed_parts=" + Math.max(0, parsedParts) +
                " mime_candidates=" + Math.max(0, mimeCandidates) +
                " remote_candidates=" + Math.max(0, remoteCandidates) +
                " fallback_rows=" + Math.max(0, fallbackRows) +
                " rows_inserted=" + Math.max(0, rowsInserted);
    }

    static void logBodyOpenDecision(Context context, String provider, String decision,
                                    EntityFolder folder, EntityMessage message,
                                    boolean providerIdPresent, boolean operationPending) {
        if (context != null)
            EntityLog.logProtocol(context, formatBodyOpenDecision(provider, decision,
                    folder != null && Boolean.TRUE.equals(folder.download),
                    message != null && message.content, providerIdPresent, operationPending));
    }

    static String formatBodyOpenDecision(String provider, String decision,
                                         boolean folderDownload, boolean content,
                                         boolean providerIdPresent, boolean operationPending) {
        String safeDecision;
        switch (decision == null ? "" : decision) {
            case "queued":
            case "already_pending":
            case "already_complete":
            case "folder_download_disabled":
            case "provider_id_missing":
            case "owner_mismatch":
            case "folder_missing":
            case "message_missing":
            case "unsupported_provider":
                safeDecision = decision;
                break;
            default:
                safeDecision = "unknown";
        }
        return "Native provider=" + safeProvider(provider) + " operation=body event=open" +
                " decision=" + safeDecision + " folder_download=" + folderDownload +
                " content=" + content + " provider_id_present=" + providerIdPresent +
                " operation_pending=" + operationPending;
    }

    static void logBodyCacheMiss(Context context, String provider, boolean queued) {
        if (context != null)
            EntityLog.logProtocol(context, formatBodyCacheMiss(provider, queued));
    }

    static String formatBodyCacheMiss(String provider, boolean queued) {
        return "Native provider=" + safeProvider(provider) + " operation=body event=cache_miss" +
                " queued=" + queued;
    }

    static void logContentOperationEvent(Context context, String provider,
                                         String operation, String event) {
        if (context != null)
            EntityLog.logProtocol(context, formatContentOperationEvent(provider, operation, event));
    }

    static String formatContentOperationEvent(String provider, String operation, String event) {
        String safeOperation = ("body".equals(operation) || "attachment".equals(operation)
                ? operation : "unknown");
        String safeEvent = ("queued".equals(event) || "started".equals(event)
                ? event : "unknown");
        return "Native provider=" + safeProvider(provider) + " operation=" + safeOperation +
                " event=" + safeEvent;
    }

    static void logAttachmentComplete(Context context, String provider, long bytes, boolean cacheHit) {
        if (context != null)
            EntityLog.logProtocol(context, formatAttachmentComplete(provider, bytes, cacheHit));
    }

    static String formatAttachmentComplete(String provider, long bytes, boolean cacheHit) {
        return "Native provider=" + safeProvider(provider) + " operation=attachment event=complete" +
                " success=true bytes=" + Math.max(0, bytes) + " cache_hit=" + cacheHit;
    }

    static void logContentOperationFailure(Context context, String provider,
                                           String operation, Throwable error) {
        if (context != null)
            EntityLog.logProtocol(context, formatContentOperationFailure(provider, operation, error));
    }

    static String formatContentOperationFailure(String provider, String operation, Throwable error) {
        String safeOperation = ("body".equals(operation) || "attachment".equals(operation)
                ? operation : "unknown");
        String failure = "other";
        int status = -1;
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
            if (cause instanceof GmailApi.ApiException) {
                failure = "http";
                status = ((GmailApi.ApiException) cause).status;
                break;
            } else if (cause instanceof ZohoApi.ApiException) {
                failure = "http";
                status = ((ZohoApi.ApiException) cause).status;
                break;
            } else if (cause instanceof GraphMailApi.ApiException) {
                failure = "http";
                status = ((GraphMailApi.ApiException) cause).status;
                break;
            } else if (cause instanceof android.os.OperationCanceledException)
                failure = "cancelled";
            else if (cause instanceof java.net.SocketTimeoutException)
                failure = "timeout";
            else if (cause instanceof org.json.JSONException)
                failure = "json";
            else if (cause instanceof javax.mail.MessagingException)
                failure = "mime";
            else if (cause instanceof java.io.IOException && "other".equals(failure))
                failure = "io";
        }
        if (status < 100 || status > 599)
            status = -1;
        return "Native provider=" + safeProvider(provider) + " operation=" + safeOperation +
                " event=failed failure=" + failure + " status=" + status;
    }

    static void logGmailCidQueued(Context context, int count) {
        logGmailCid(context, "queued", true, count, -1, false);
    }

    static void logGmailCidWorkerStarted(Context context) {
        logGmailCid(context, "worker-started", true, 1, -1, false);
    }

    static void logGmailCidFetchComplete(Context context, boolean success, long bytes, boolean cacheHit) {
        logGmailCid(context, "fetch-complete", success, 1, bytes, cacheHit);
    }

    static void logGmailCidRedraw(Context context, int available) {
        if (context != null)
            EntityLog.logProtocol(context, formatGmailCidRedraw(available));
    }

    static String formatGmailCidRedraw(int available) {
        return "Native provider=gmail operation=cid_attachment event=redraw" +
                " success=true available=" + Math.max(0, available);
    }

    private static void logGmailCid(Context context, String event, boolean success,
                                    int count, long bytes, boolean cacheHit) {
        if (context != null)
            EntityLog.logProtocol(context, formatGmailCid(event, success, count, bytes, cacheHit));
    }

    static String formatGmailCid(String event, boolean success, int count, long bytes, boolean cacheHit) {
        String safeEvent = ("queued".equals(event) || "worker-started".equals(event) ||
                "fetch-complete".equals(event) ? event : "unknown");
        StringBuilder result = new StringBuilder("Native provider=gmail operation=cid_attachment event=")
                .append(safeEvent).append(" success=").append(success)
                .append(" count=").append(Math.max(0, count));
        if ("fetch-complete".equals(safeEvent))
            result.append(" bytes=").append(bytes).append(" cache_hit=").append(cacheHit);
        return result.toString();
    }

    private static String safeProvider(String provider) {
        return ("gmail".equals(provider) || "zoho".equals(provider) || "graph".equals(provider)
                ? provider : "unknown");
    }

    private static final class GraphError {
        static final GraphError EMPTY = new GraphError(null, null, null);
        final String code;
        final String innerCode;
        final String message;

        GraphError(String code, String innerCode, String message) {
            this.code = code;
            this.innerCode = innerCode;
            this.message = message;
        }
    }
}
