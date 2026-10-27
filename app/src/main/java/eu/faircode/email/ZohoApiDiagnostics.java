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

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.mail.internet.ContentType;

/** Privacy-limited diagnostics for the Zoho REST path, gated by Protocol logging. */
final class ZohoApiDiagnostics {
    private static final Pattern MIME_TYPE = Pattern.compile(
            "[a-zA-Z0-9!#$&^_.+-]{1,80}/[a-zA-Z0-9!#$&^_.+-]{1,80}");
    private static final Set<String> SAFE_ERROR_TOKENS = new HashSet<>(Arrays.asList(
            "BAD_REQUEST", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND", "NOT_ACCEPTABLE",
            "INTERNAL_ERROR", "INVALID_REQUEST", "INVALID_DATA", "INVALID_PARAMETER",
            "INVALID_TOKEN", "INVALID_OAUTH_TOKEN", "RESOURCE_NOT_FOUND",
            "METHOD_NOT_ALLOWED", "UNSUPPORTED_MEDIA_TYPE", "AUTHENTICATION_FAILURE",
            "PERMISSION_DENIED"));
    // Process-local salt permits correlation within one diagnostic report without exposing IDs.
    private static final String CORRELATION_SALT = UUID.randomUUID().toString();

    private ZohoApiDiagnostics() {
    }

    static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    static String inlineScopeTag(String folderId, String messageId) {
        if (folderId == null || folderId.trim().isEmpty() ||
                messageId == null || messageId.trim().isEmpty())
            return "unknown";
        return privateTag("scope", folderId.trim(), messageId.trim());
    }

    static String inlineCidTag(String contentId) {
        if (contentId == null)
            return "unknown";
        String value = contentId.trim();
        if (value.length() >= 2 && value.startsWith("<") && value.endsWith(">"))
            value = value.substring(1, value.length() - 1).trim();
        return (value.isEmpty() ? "unknown" : privateTag("cid", value));
    }

    static String formatMessageScope(String stage, String method, String path,
                                     String folderId, String messageId) {
        String safeStage = ("content".equals(stage) || "attachment_info".equals(stage)
                ? stage : "unknown");
        String safeMethod = ("GET".equalsIgnoreCase(method) ? "GET" : "other");
        return "Zoho REST message scope" +
                " stage=" + safeStage +
                " method=" + safeMethod +
                " route=" + sanitizeRoute(path) +
                " scope_tag=" + safeCorrelationTag(inlineScopeTag(folderId, messageId));
    }

    static void logMessageScope(Context context, String stage, String method, String path,
                                String folderId, String messageId) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatMessageScope(stage, method, path, folderId, messageId));
    }

    static String formatInlineCidAssociation(String folderId, String messageId, String contentId,
                                             boolean cidRowPresent, boolean inlineImageRow,
                                             boolean sourceMatchesRow) {
        return "Zoho REST inline CID mapping" +
                " scope_tag=" + safeCorrelationTag(inlineScopeTag(folderId, messageId)) +
                " cid_tag=" + safeCorrelationTag(inlineCidTag(contentId)) +
                " cid_row_present=" + cidRowPresent +
                " inline_image_row=" + inlineImageRow +
                " source_matches_row=" + sourceMatchesRow;
    }

    static void logInlineCidAssociation(Context context, String folderId, String messageId,
                                        String contentId, boolean cidRowPresent,
                                        boolean inlineImageRow, boolean sourceMatchesRow) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatInlineCidAssociation(folderId, messageId,
                contentId, cidRowPresent, inlineImageRow, sourceMatchesRow));
    }

    static String formatInlineRequestContext(long sequence, int attempt, String method,
                                             String path, String accept, String contentType,
                                             String scopeTag, String cidTag, String versionName,
                                             int versionCode, String sourceCommit) {
        String safeMethod = ("GET".equalsIgnoreCase(method) ? "GET" : "other");
        String safeVersion = (versionName != null &&
                versionName.matches("[A-Za-z0-9._+-]{1,40}") ? versionName : "unknown");
        String safeCommit = (sourceCommit != null && sourceCommit.matches("(?i)[0-9a-f]{40}")
                ? sourceCommit.toLowerCase(Locale.ROOT) : "unknown");
        return "Zoho REST inline request" +
                " seq=" + Math.max(0, sequence) +
                " attempt=" + Math.max(1, attempt) +
                " method=" + safeMethod +
                " route=" + sanitizeRoute(path) +
                " configured_accept=" + safeRequestMediaValue(accept) +
                " configured_content_type=" + safeRequestMediaValue(contentType) +
                " scope_tag=" + safeCorrelationTag(scopeTag) +
                " cid_tag=" + safeCorrelationTag(cidTag) +
                " app_version=" + safeVersion +
                " version_code=" + Math.max(0, versionCode) +
                " source_commit=" + safeCommit;
    }

    static void logInlineRequestContext(Context context, long sequence, int attempt,
                                        String method, String path, String accept,
                                        String contentType, String scopeTag, String cidTag,
                                        String versionName, int versionCode, String sourceCommit) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatInlineRequestContext(sequence, attempt, method,
                path, accept, contentType, scopeTag, cidTag, versionName, versionCode, sourceCommit));
    }

    static final class InlineRequestCorrelation {
        final String accountId;
        final String folderId;
        final String messageId;
        final String requestContentId;
        final Long attachmentRowId;
        final Long attachmentMessageRowId;
        final String attachmentProviderId;
        final String attachmentProviderFolder;
        final String attachmentContentId;

        InlineRequestCorrelation(String accountId, String folderId, String messageId,
                                 String requestContentId, Long attachmentRowId,
                                 Long attachmentMessageRowId, String attachmentProviderId,
                                 String attachmentProviderFolder, String attachmentContentId) {
            this.accountId = accountId;
            this.folderId = folderId;
            this.messageId = messageId;
            this.requestContentId = requestContentId;
            this.attachmentRowId = attachmentRowId;
            this.attachmentMessageRowId = attachmentMessageRowId;
            this.attachmentProviderId = attachmentProviderId;
            this.attachmentProviderFolder = attachmentProviderFolder;
            this.attachmentContentId = attachmentContentId;
        }
    }

    static String formatInline406Correlation(long sequence, int attempt, String method,
                                             String path, int status, long elapsedMillis,
                                             String responseType, long responseBytes,
                                             long contentLengthHeader, String responseBody,
                                             String bodyReadState, String accept,
                                             String contentType,
                                             InlineRequestCorrelation request) {
        if (request == null || status != 406 || method == null ||
                !"GET".equalsIgnoreCase(method) || !isInlineImageErrorPath(path) ||
                !("complete".equals(bodyReadState) || "empty".equals(bodyReadState)))
            return null;

        JSONObject root = parseCompleteInlineError(responseBody, bodyReadState);
        JSONObject statusObject = (root == null ? null : root.optJSONObject("status"));
        JSONObject errorObject = (root == null ? null : root.optJSONObject("error"));
        String requestId = diagnosticResponseString(root, "requestId", 128);
        if (requestId != null && !requestId.matches("[A-Za-z0-9._:-]{1,128}"))
            requestId = null;
        String responseStatusCode = diagnosticResponseNumber(
                (statusObject == null ? null : statusObject.opt("code")));
        String responseStatusDescription = diagnosticResponseString(statusObject, "description", 128);
        String errorCode = normalizeSafeErrorToken(
                diagnosticResponseString(errorObject, "errorCode", 64));
        String errorMessage = diagnosticResponseString(errorObject, "errorMessage", 512);
        String errorLocation = diagnosticResponseString(errorObject, "location", 128);
        String errorLocationType = diagnosticResponseString(errorObject, "locationType", 64);
        String jsonState = (root == null ? ("empty".equals(bodyReadState) ? "empty" : "invalid_json") : "json");
        String cidMatch = (request.attachmentContentId == null ? "unknown" :
                Boolean.toString(sameContentId(request.requestContentId, request.attachmentContentId)));
        String folderMatch = (request.attachmentProviderFolder == null ? "unknown" :
                Boolean.toString(request.attachmentProviderFolder.equals(request.folderId)));

        long header = Math.max(-1, contentLengthHeader);
        long actual = Math.max(-1, responseBytes);
        String lengthCheck = (header < 0 || actual < 0 ? "unknown" :
                (header == actual ? "match" : "mismatch"));
        return "Zoho REST inline 406 correlation" +
                " seq=" + Math.max(0, sequence) +
                " attempt=" + Math.max(1, attempt) +
                " method=GET" +
                " route=" + sanitizeRoute(path) +
                " status=406" +
                " elapsed_ms=" + Math.max(0, elapsedMillis) +
                " account_id=" + quoteDiagnosticValue(request.accountId) +
                " folder_id=" + quoteDiagnosticValue(request.folderId) +
                " message_id=" + quoteDiagnosticValue(request.messageId) +
                " request_content_id=" + quoteDiagnosticValue(request.requestContentId) +
                " metadata_attachment_row_id=" + diagnosticNumber(request.attachmentRowId) +
                " metadata_message_row_id=" + diagnosticNumber(request.attachmentMessageRowId) +
                " metadata_provider_id=" + quoteDiagnosticValue(request.attachmentProviderId) +
                " metadata_provider_folder=" + quoteDiagnosticValue(request.attachmentProviderFolder) +
                " metadata_content_id=" + quoteDiagnosticValue(request.attachmentContentId) +
                " metadata_folder_matches_request=" + folderMatch +
                " metadata_cid_matches_request=" + cidMatch +
                " configured_accept=" + safeRequestMediaValue(accept) +
                " configured_content_type=" + safeRequestMediaValue(contentType) +
                " response_type=" + sanitizeMimeType(responseType) +
                " response_bytes=" + Math.max(-1, responseBytes) +
                " content_length_header=" + header +
                " length_check=" + lengthCheck +
                " body_read_state=" + bodyReadState +
                " response_json_state=" + jsonState +
                " request_id=" + quoteDiagnosticValue(requestId) +
                " response_status_code=" + (responseStatusCode == null ? "unknown" : responseStatusCode) +
                " response_status_description=" + quoteDiagnosticValue(responseStatusDescription) +
                " error_code=" + (errorCode == null ? "unknown" : errorCode) +
                " error_message=" + quoteDiagnosticValue(errorMessage) +
                " error_location=" + quoteDiagnosticValue(errorLocation) +
                " error_location_type=" + quoteDiagnosticValue(errorLocationType);
    }

    static void logInline406Correlation(Context context, long sequence, int attempt,
                                        String method, String path, int status,
                                        long elapsedMillis, String responseType,
                                        long responseBytes, long contentLengthHeader,
                                        String responseBody, String bodyReadState,
                                        String accept, String contentType,
                                        InlineRequestCorrelation request) {
        if (!isEnabled(context))
            return;
        String entry = formatInline406Correlation(sequence, attempt, method, path, status,
                elapsedMillis, responseType, responseBytes, contentLengthHeader,
                responseBody, bodyReadState, accept, contentType, request);
        if (entry != null)
            EntityLog.logProtocol(context, entry);
    }

    private static JSONObject parseCompleteInlineError(String body, String readState) {
        if (!"complete".equals(readState) || body == null)
            return null;
        try {
            JSONTokener tokener = new JSONTokener(body);
            Object value = tokener.nextValue();
            return (value instanceof JSONObject && tokener.nextClean() == 0 ? (JSONObject) value : null);
        } catch (JSONException | RuntimeException ex) {
            return null;
        }
    }

    private static String diagnosticResponseString(JSONObject object, String name, int maxLength) {
        if (object == null)
            return null;
        Object value = object.opt(name);
        return (value instanceof String && ((String) value).length() <= maxLength
                ? (String) value : null);
    }

    private static String diagnosticResponseNumber(Object value) {
        if (!(value instanceof Number))
            return null;
        double number = ((Number) value).doubleValue();
        return (number >= 100 && number <= 599 && number == Math.rint(number)
                ? Integer.toString((int) number) : null);
    }

    private static String diagnosticNumber(Long value) {
        return (value == null ? "unknown" : Long.toString(value));
    }

    private static String quoteDiagnosticValue(String value) {
        if (value == null)
            return "null";
        if (value.length() > 4096)
            return JSONObject.quote("<oversized:" + value.length() + ">");
        return JSONObject.quote(value).replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    private static boolean sameContentId(String left, String right) {
        return (normalizeContentId(left) != null &&
                normalizeContentId(left).equals(normalizeContentId(right)));
    }

    private static String normalizeContentId(String value) {
        if (value == null)
            return null;
        String normalized = value.trim();
        if (normalized.startsWith("<") && normalized.endsWith(">"))
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        return (normalized.isEmpty() ? null : normalized);
    }

    private static String safeRequestMediaValue(String value) {
        if (value == null)
            return "none";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return ("application/json".equals(normalized) || "image/*".equals(normalized) ||
                "*/*".equals(normalized) ||
                "application/octet-stream".equals(normalized) ? normalized : "other");
    }

    private static String safeCorrelationTag(String value) {
        return (value != null && (value.matches("[0-9a-f]{24}") ||
                "unknown".equals(value) || "unavailable".equals(value)) ? value : "unknown");
    }

    private static String privateTag(String purpose, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(CORRELATION_SALT.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(purpose.getBytes(StandardCharsets.UTF_8));
            for (String value : values) {
                if (value == null || value.length() > 2048)
                    return "unknown";
                digest.update((byte) 0);
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = digest.digest();
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder tag = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                int b = hash[i] & 0xff;
                tag.append(hex[b >>> 4]).append(hex[b & 0x0f]);
            }
            return tag.toString();
        } catch (Exception ex) {
            return "unavailable";
        }
    }

    static boolean isTraceEnabled(Context context) {
        return isEnabled(context);
    }

    static void logTrace(Context context, String traceId, String stage, String details) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, "Zoho import trace=" + safeTraceId(traceId) +
                " stage=" + safeTraceStage(stage) +
                (details == null || details.isEmpty() ? "" : " " + safeTraceDetails(details)));
    }

    static void logTracePart(Context context, String traceId, String stage,
                             String path, int index, int count, String partClass,
                             String contentType, String disposition, String classification,
                             long bytes, long chars, String contentClass, boolean selected) {
        if (!isEnabled(context))
            return;
        String safePath = (path != null && path.matches("(?:root|body|extra|attachment)(?:/[0-9]{1,6}){0,32}")
                ? path : "unknown");
        EntityLog.logProtocol(context, "Zoho import trace=" + safeTraceId(traceId) +
                " stage=" + safeTraceStage(stage) +
                " path=" + safePath +
                " part_index=" + Math.max(-1, index) +
                " part_count=" + Math.max(-1, count) +
                " part_class=" + safeClass(partClass) +
                " content_type_exact=" + safeExactContentType(contentType) +
                " disposition=" + safeDisposition(disposition) +
                " class=" + safeClassification(classification) +
                " bytes=" + Math.max(-1, bytes) +
                " chars=" + Math.max(-1, chars) +
                " get_content_class=" + safeContentClass(contentClass) +
                " selected=" + selected);
    }

    private static String safeTraceId(String value) {
        return (value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                ? value : "unknown");
    }

    private static String safeTraceStage(String value) {
        return ("cache_lookup".equals(value) || "content_http_response".equals(value) ||
                "content_response".equals(value) || "body_input".equals(value) ||
                "body_normalized".equals(value) || "synthetic_mime".equals(value) ||
                "helper_parts".equals(value) || "helper_leaf".equals(value) ||
                "renderer_leaf".equals(value) || "renderer_match".equals(value) ||
                "renderer_fallback".equals(value) || "renderer_output".equals(value) ||
                "body_persisted".equals(value) || "message_linked".equals(value)
                ? value : "unknown");
    }

    private static String safeTraceDetails(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9_.,;:/=<>{} -]", "_");
        return (safe.length() > 900 ? safe.substring(0, 900) : safe);
    }

    private static String safeClass(String value) {
        return (value != null && value.matches("[A-Za-z0-9_.$]{1,80}") ? value : "unknown");
    }

    private static String safeContentClass(String value) {
        return ("String".equals(value) || "InputStream".equals(value) ||
                "Multipart".equals(value) || "null".equals(value) ||
                "unread".equals(value) ? value : "other");
    }

    private static String safeClassification(String value) {
        return ("container".equals(value) || "plain".equals(value) ||
                "html".equals(value) || "resource".equals(value) ||
                "attachment".equals(value) || "other".equals(value)
                ? value : "other");
    }

    private static String safeDisposition(String value) {
        if (value == null)
            return "none";
        String base = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return ("inline".equals(base) || "attachment".equals(base) ||
                "form-data".equals(base) ? base : "other");
    }

    private static String safeExactContentType(String value) {
        if (value == null)
            return "none";
        try {
            ContentType contentType = new ContentType(value);
            StringBuilder safe = new StringBuilder(sanitizeMimeType(contentType.getBaseType()));
            ArrayList<String> names = new ArrayList<>();
            if (contentType.getParameterList() != null) {
                Enumeration<?> parameters = contentType.getParameterList().getNames();
                while (parameters.hasMoreElements()) {
                    Object next = parameters.nextElement();
                    if (next instanceof String && ((String) next).matches("[A-Za-z0-9_-]{1,40}"))
                        names.add(((String) next).toLowerCase(Locale.ROOT));
                }
            }
            Collections.sort(names);
            for (String name : names) {
                if ("charset".equals(name)) {
                    String charset = contentType.getParameter(name);
                    if (charset != null && charset.matches("[A-Za-z0-9._+-]{1,48}"))
                        safe.append("; charset=").append(charset);
                } else if ("boundary".equals(name))
                    safe.append("; boundary=<set>");
                else
                    safe.append("; ").append(name).append("=<omitted>");
            }
            return safe.toString();
        } catch (Exception ex) {
            return sanitizeMimeType(value);
        }
    }

    static String safeContentType(String value) {
        return safeExactContentType(value);
    }

    static void logExchange(Context context, long sequence, String method, String path,
                            int attempt, int status, long elapsedMillis,
                            String requestType, long requestBytes,
                            String responseType, long responseChars, String outcome) {
        logExchange(context, sequence, method, path, attempt, status, elapsedMillis,
                requestType, requestBytes, responseType, responseChars, outcome, null);
    }

    static void logExchange(Context context, long sequence, String method, String path,
                            int attempt, int status, long elapsedMillis,
                            String requestType, long requestBytes,
                            String responseType, long responseChars, String outcome,
                            String traceId) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatExchange(sequence, method, path, attempt, status,
                elapsedMillis, requestType, requestBytes, responseType, responseChars,
                outcome, traceId));
    }

    static String formatExchange(long sequence, String method, String path,
                                 int attempt, int status, long elapsedMillis,
                                 String requestType, long requestBytes,
                                 String responseType, long responseChars, String outcome) {
        return formatExchange(sequence, method, path, attempt, status, elapsedMillis,
                requestType, requestBytes, responseType, responseChars, outcome, null);
    }

    static String formatExchange(long sequence, String method, String path,
                                 int attempt, int status, long elapsedMillis,
                                 String requestType, long requestBytes,
                                 String responseType, long responseChars, String outcome,
                                 String traceId) {
        String safeMethod = (method != null && method.matches("[A-Za-z]{1,12}")
                ? method.toUpperCase(Locale.ROOT) : "UNKNOWN");
        String safeOutcome = sanitizeOutcome(outcome);
        return "Zoho REST exchange seq=" + Math.max(0, sequence) +
                (traceId == null ? "" : " trace=" + safeTraceId(traceId)) +
                " method=" + safeMethod +
                " route=" + sanitizeRoute(path) +
                " attempt=" + Math.max(1, attempt) +
                " status=" + (status >= 100 && status <= 599 ? status : -1) +
                " elapsed_ms=" + Math.max(0, elapsedMillis) +
                " request_type=" + sanitizeMimeType(requestType) +
                " request_bytes=" + Math.max(-1, requestBytes) +
                " response_type=" + sanitizeMimeType(responseType) +
                " response_chars=" + Math.max(-1, responseChars) +
                " outcome=" + safeOutcome;
    }

    static void logBinaryExchange(Context context, long sequence, String method, String path,
                                  int attempt, int status, long elapsedMillis,
                                  String responseType, long responseBytes, String outcome) {
        logBinaryExchange(context, sequence, method, path, attempt, status, elapsedMillis,
                responseType, responseBytes, outcome, null, null);
    }

    static void logBinaryExchange(Context context, long sequence, String method, String path,
                                  int attempt, int status, long elapsedMillis,
                                  String responseType, long responseBytes, String outcome,
                                  String errorCode, String errorDescription) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatBinaryExchange(sequence, method, path, attempt,
                status, elapsedMillis, responseType, responseBytes, outcome,
                errorCode, errorDescription));
    }

    static String formatBinaryExchange(long sequence, String method, String path,
                                       int attempt, int status, long elapsedMillis,
                                       String responseType, long responseBytes, String outcome) {
        return formatBinaryExchange(sequence, method, path, attempt, status, elapsedMillis,
                responseType, responseBytes, outcome, null, null);
    }

    static String formatBinaryExchange(long sequence, String method, String path,
                                       int attempt, int status, long elapsedMillis,
                                       String responseType, long responseBytes, String outcome,
                                       String errorCode, String errorDescription) {
        String safeMethod = (method != null && method.matches("[A-Za-z]{1,12}")
                ? method.toUpperCase(Locale.ROOT) : "UNKNOWN");
        String safeOutcome = sanitizeOutcome(outcome);
        String safeErrorCode = sanitizeErrorCode(errorCode);
        String safeErrorDescription = sanitizeErrorDescription(errorDescription);
        return "Zoho REST binary exchange seq=" + Math.max(0, sequence) +
                " method=" + safeMethod +
                " route=" + sanitizeRoute(path) +
                " attempt=" + Math.max(1, attempt) +
                " status=" + (status >= 100 && status <= 599 ? status : -1) +
                " elapsed_ms=" + Math.max(0, elapsedMillis) +
                " response_type=" + sanitizeMimeType(responseType) +
                " response_bytes=" + Math.max(-1, responseBytes) +
                (safeErrorCode == null ? "" : " error_code=" + safeErrorCode) +
                (safeErrorDescription == null ? "" : " error_description=" + safeErrorDescription) +
                " outcome=" + safeOutcome;
    }

    static final class JsonErrorSummary {
        final String code;
        final String reason;
        final String fieldTypes;
        final String parseState;

        JsonErrorSummary(String code, String reason, String fieldTypes, String parseState) {
            this.code = code;
            this.reason = reason;
            this.fieldTypes = fieldTypes;
            this.parseState = parseState;
        }
    }

    static JsonErrorSummary summarizeInlineError(int httpStatus, String responseBody,
                                                  String readState) {
        String state = safeErrorBodyState(readState);
        if (!"complete".equals(state))
            return new JsonErrorSummary(null, null, "", state);
        if (responseBody == null || responseBody.trim().isEmpty())
            return new JsonErrorSummary(null, null, "", "empty");

        try {
            JSONTokener tokener = new JSONTokener(responseBody);
            Object root = tokener.nextValue();
            if (tokener.nextClean() != 0)
                return new JsonErrorSummary(null, null, "", "invalid_json");

            StringBuilder fields = new StringBuilder();
            appendJsonFieldTypes(root, "", fields, 0);
            String[] code = new String[1];
            String[] reason = new String[1];
            collectSafeErrorDetails(root, httpStatus, code, reason, 0);
            String parseState = (root instanceof JSONObject || root instanceof JSONArray
                    ? "json" : "invalid_json");
            return new JsonErrorSummary(code[0], reason[0], fields.toString(), parseState);
        } catch (JSONException | RuntimeException ex) {
            return new JsonErrorSummary(null, null, "", "invalid_json");
        }
    }

    static String formatInlineHttpErrorExchange(long sequence, String method, String path,
                                                int attempt, int status, long elapsedMillis,
                                                String responseType, long responseBytes,
                                                long contentLengthHeader, String responseBody,
                                                String bodyReadState) {
        JsonErrorSummary summary = summarizeInlineError(status, responseBody, bodyReadState);
        String base = formatBinaryExchange(sequence, method, path, attempt, status,
                elapsedMillis, responseType, responseBytes, "http_error",
                summary.code, summary.reason);
        long header = Math.max(-1, contentLengthHeader);
        long actual = Math.max(-1, responseBytes);
        String lengthCheck = (header < 0 || actual < 0 ? "unknown" :
                (header == actual ? "match" : "mismatch"));
        StringBuilder entry = new StringBuilder(base + " content_length_header=" + header +
                " length_check=" + lengthCheck +
                " error_body_state=" + summary.parseState +
                " json_fields=" + (summary.fieldTypes.isEmpty()
                        ? "none" : "[" + summary.fieldTypes + "]"));
        boolean completeBody = ("complete".equals(bodyReadState) && responseBody != null);
        boolean emptyBody = ("empty".equals(bodyReadState) &&
                (responseBody == null || responseBody.isEmpty()));
        if (status == 406 && method != null && "GET".equalsIgnoreCase(method) &&
                isInlineImageErrorPath(path) && (completeBody || emptyBody)) {
            // JSON quoting prevents log-line injection; decoding the field recovers the exact body.
            String quotedBody = JSONObject.quote(responseBody == null ? "" : responseBody)
                    .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
            entry.append(" error_body_raw=").append(quotedBody);
        }
        return entry.toString();
    }

    private static boolean isInlineImageErrorPath(String path) {
        return (path != null && path.contains("?contentId=") &&
                "/api/accounts/{account}/folders/{folder}/messages/{message}/inline".equals(
                        sanitizeRoute(path)));
    }

    static void logInlineHttpErrorExchange(Context context, long sequence, String method,
                                           String path, int attempt, int status,
                                           long elapsedMillis, String responseType,
                                           long responseBytes, long contentLengthHeader,
                                           String responseBody, String bodyReadState) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatInlineHttpErrorExchange(sequence, method, path,
                attempt, status, elapsedMillis, responseType, responseBytes,
                contentLengthHeader, responseBody, bodyReadState));
    }

    private static String safeErrorBodyState(String value) {
        return ("complete".equals(value) || "empty".equals(value) ||
                "oversized".equals(value) || "read_error".equals(value) ||
                "unavailable".equals(value) ? value : "unknown");
    }

    private static void appendJsonFieldTypes(Object value, String path,
                                             StringBuilder result, int depth) {
        if (depth > 32)
            return;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            TreeMap<String, Object> sorted = new TreeMap<>();
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                sorted.put(key, object.opt(key));
            }
            for (java.util.Map.Entry<String, Object> entry : sorted.entrySet()) {
                String field = (path.isEmpty() ? safeJsonKey(entry.getKey())
                        : path + "." + safeJsonKey(entry.getKey()));
                if (result.length() > 0)
                    result.append(';');
                result.append(field).append(':').append(jsonType(entry.getValue()));
                appendJsonFieldTypes(entry.getValue(), field, result, depth + 1);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++)
                appendJsonFieldTypes(array.opt(i), path + "[" + i + "]", result, depth + 1);
        }
    }

    private static String safeJsonKey(String value) {
        if (value == null || value.length() > 96)
            return "redacted_key";
        String safe = value
                .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "<redacted>")
                .replaceAll("(?i)https?://[^\\s\\\"']+", "<redacted>")
                .replaceAll("[A-Za-z0-9_-]{20,}", "<id>")
                .replaceAll("[0-9]{6,}", "<id>");
        if (!safe.matches("[A-Za-z_][A-Za-z0-9_.<>-]{0,95}"))
            return "redacted_key";
        return safe;
    }

    private static String jsonType(Object value) {
        if (value == null || value == JSONObject.NULL)
            return "null";
        if (value instanceof JSONObject)
            return "object";
        if (value instanceof JSONArray)
            return "array";
        if (value instanceof String)
            return "string";
        if (value instanceof Number)
            return "number";
        if (value instanceof Boolean)
            return "boolean";
        return "other";
    }

    private static void collectSafeErrorDetails(Object value, int httpStatus,
                                                String[] code, String[] reason, int depth) {
        if (!(value instanceof JSONObject) || depth > 32)
            return;
        JSONObject object = (JSONObject) value;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object child = object.opt(key);
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (code[0] == null && ("code".equals(normalizedKey) ||
                    "error_code".equals(normalizedKey) || "status_code".equals(normalizedKey)))
                code[0] = safeErrorValue(httpStatus, child);
            if (reason[0] == null && ("description".equals(normalizedKey) ||
                    "error_description".equals(normalizedKey) || "reason".equals(normalizedKey) ||
                    "error".equals(normalizedKey) || "message".equals(normalizedKey)))
                reason[0] = safeErrorReason(child);
            if (child instanceof JSONObject)
                collectSafeErrorDetails(child, httpStatus, code, reason, depth + 1);
            else if (child instanceof JSONArray) {
                JSONArray array = (JSONArray) child;
                for (int i = 0; i < array.length(); i++)
                    collectSafeErrorDetails(array.opt(i), httpStatus, code, reason, depth + 1);
            }
        }
    }

    private static String safeErrorValue(int httpStatus, Object value) {
        if (value instanceof Number && ((Number) value).doubleValue() == httpStatus &&
                httpStatus >= 400 && httpStatus <= 599)
            return Integer.toString(httpStatus);
        if (!(value instanceof String))
            return null;
        String text = ((String) value).trim();
        if (text.matches("[0-9]{3}") && text.equals(Integer.toString(httpStatus)) &&
                httpStatus >= 400 && httpStatus <= 599)
            return text;
        return normalizeSafeErrorToken(text);
    }

    private static String safeErrorReason(Object value) {
        return (value instanceof String ? normalizeSafeErrorToken((String) value) : null);
    }

    private static String normalizeSafeErrorToken(String value) {
        if (value == null || value.length() > 64)
            return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace(' ', '_').replace('-', '_');
        return (SAFE_ERROR_TOKENS.contains(normalized) ? normalized : null);
    }

    static void logNormalization(Context context, long sequence, String inputKind,
                                 long inputChars, String outputType, long outputChars,
                                 boolean success, String mimePath,
                                 int textPlainParts, int textHtmlParts,
                                 int inlineResources, int inlineImages,
                                 int cidReferences) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatNormalization(sequence, inputKind,
                inputChars, outputType, outputChars, success, mimePath,
                textPlainParts, textHtmlParts, inlineResources, inlineImages,
                cidReferences));
    }

    static String formatNormalization(long sequence, String inputKind,
                                      long inputChars, String outputType, long outputChars,
                                      boolean success, String mimePath,
                                      int textPlainParts, int textHtmlParts,
                                      int inlineResources, int inlineImages,
                                      int cidReferences) {
        return "Zoho REST body normalize" +
                " request_seq=" + Math.max(0, sequence) +
                " input=" + safeInputKind(inputKind) +
                " input_chars=" + Math.max(0, inputChars) +
                " source_path=" + sanitizeMimePath(mimePath) +
                " text_plain_parts=" + Math.max(0, textPlainParts) +
                " text_html_parts=" + Math.max(0, textHtmlParts) +
                " inline_resources=" + Math.max(0, inlineResources) +
                " inline_images=" + Math.max(0, inlineImages) +
                " cid_refs=" + Math.max(0, cidReferences) +
                " output_type=" + sanitizeMimeType(outputType) +
                " output_chars=" + Math.max(0, outputChars) +
                " normalizer_input_len=" + Math.max(0, inputChars) +
                " normalizer_output_len=" + Math.max(0, outputChars) +
                " normalizer_output_is_html=" + "text/html".equals(sanitizeMimeType(outputType)) +
                " outcome=" + (success ? "success" : "failure");
    }

    static void logBodyFetch(Context context, long sequence, long contentLengthHeader,
                             long actualResponseBytes, long contentChars) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatBodyFetch(sequence, contentLengthHeader,
                actualResponseBytes, contentChars));
    }

    static void logTraceBodyFetch(Context context, String traceId, long sequence,
                                  long contentLengthHeader, long actualResponseBytes,
                                  long responseJsonChars) {
        logTrace(context, traceId, "content_http_response",
                "request_seq=" + Math.max(0, sequence) +
                        " content_length_header=" + Math.max(-1, contentLengthHeader) +
                        " raw_response_bytes=" + Math.max(-1, actualResponseBytes) +
                        " response_json_chars=" + Math.max(-1, responseJsonChars));
    }

    static void logTraceContentResponse(Context context, String traceId, long sequence,
                                        long contentLengthHeader, long responseBytes,
                                        long responseJsonChars, String responseType,
                                        boolean dataPresent, boolean contentIsString,
                                        String content) {
        if (!isEnabled(context))
            return;
        String kind = ZohoContentNormalizer.sourceKind(content);
        logTrace(context, traceId, "content_response",
                "request_seq=" + Math.max(0, sequence) +
                        " content_length_header=" + Math.max(-1, contentLengthHeader) +
                        " raw_response_bytes=" + Math.max(-1, responseBytes) +
                        " response_json_chars=" + Math.max(-1, responseJsonChars) +
                        " response_type=" + sanitizeMimeType(responseType) +
                        " data_present=" + dataPresent +
                        " content_is_string=" + contentIsString +
                        " decoded_body_chars=" + (content == null ? -1 : content.length()) +
                        " decoded_body_bytes=" + (content == null ? -1 :
                                content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length) +
                        " body_classification=" + safeInputKind(kind) +
                        " boundary=" + ZohoContentNormalizer.hasMultipartBoundary(content) +
                        " cid_query_count=" + ZohoContentNormalizer.countImageDisplayCidReferences(content) +
                        " cid_ref_count=" + ZohoContentNormalizer.countCidReferences(content));
    }

    static String contentClass(Object content) {
        if (content == null)
            return "null";
        if (content instanceof String)
            return "String";
        if (content instanceof java.io.InputStream)
            return "InputStream";
        if (content instanceof javax.mail.Multipart)
            return "Multipart";
        return "other";
    }

    static String formatBodyFetch(long sequence, long contentLengthHeader,
                                  long actualResponseBytes, long contentChars) {
        long header = Math.max(-1, contentLengthHeader);
        long actual = Math.max(-1, actualResponseBytes);
        String check = (header < 0 || actual < 0 ? "unknown"
                : (header == actual ? "match" : "mismatch"));
        return "Zoho REST body fetch" +
                " request_seq=" + Math.max(0, sequence) +
                " content_length_header=" + header +
                " actual_len=" + actual +
                " length_check=" + check +
                " content_chars=" + Math.max(-1, contentChars);
    }

    static void logBodyStructure(Context context, long sequence, String stage,
                                 boolean hasBoundary, int imageDisplayCidReferences,
                                 int cidReferences) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatBodyStructure(sequence, stage, hasBoundary,
                imageDisplayCidReferences, cidReferences));
    }

    static String formatBodyStructure(long sequence, String stage, boolean hasBoundary,
                                      int imageDisplayCidReferences, int cidReferences) {
        String safeStage = ("normalizer_input".equals(stage) || "normalizer_output".equals(stage) ||
                "renderer_input".equals(stage) || "renderer_output".equals(stage)
                ? stage : "unknown");
        return "Zoho REST body structure" +
                " request_seq=" + Math.max(0, sequence) +
                " stage=" + safeStage +
                " has_boundary=" + hasBoundary +
                " has_cid_query=" + (imageDisplayCidReferences > 0) +
                " image_display_cid_count=" + Math.max(0, imageDisplayCidReferences) +
                " cid_refs=" + Math.max(0, cidReferences);
    }

    static void logRenderer(Context context, long sequence, boolean inputIsHtml,
                            boolean rendererIsHtml, boolean xPlain,
                            int multipartFallbacks) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatRenderer(sequence, inputIsHtml,
                rendererIsHtml, xPlain, multipartFallbacks));
    }

    static String formatRenderer(long sequence, boolean inputIsHtml,
                                 boolean rendererIsHtml, boolean xPlain,
                                 int multipartFallbacks) {
        String reason = (!xPlain ? "none" : (multipartFallbacks > 0
                ? "multipart_plain_fallback" : (inputIsHtml ? "other" : "plain_input")));
        return "Zoho REST renderer" +
                " request_seq=" + Math.max(0, sequence) +
                " renderer_input_is_html=" + inputIsHtml +
                " renderer_input_is_escaped=" + (xPlain && !rendererIsHtml) +
                " renderer_is_html=" + rendererIsHtml +
                " renderer_xplain=" + xPlain +
                " xplain_reason=" + reason +
                " multipart_fallbacks=" + Math.max(0, multipartFallbacks);
    }

    static void logWarningSources(Context context, long sequence, String stage,
                                  int generatedWarnings, int generatedMultipartFallbacks,
                                  boolean savedWarningPresent) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatWarningSources(sequence, stage,
                generatedWarnings, generatedMultipartFallbacks, savedWarningPresent));
    }

    static String formatWarningSources(long sequence, String stage, int generatedWarnings,
                                       int generatedMultipartFallbacks,
                                       boolean savedWarningPresent) {
        String safeStage = ("rendered".equals(stage) || "imported".equals(stage) ||
                "cache_reused".equals(stage) ? stage : "unknown");
        return "Zoho REST warning sources" +
                " request_seq=" + Math.max(0, sequence) +
                " stage=" + safeStage +
                " generated_warnings=" + Math.max(0, generatedWarnings) +
                " generated_plain_multipart_fallbacks=" +
                Math.max(0, generatedMultipartFallbacks) +
                " saved_warning_present=" + savedWarningPresent;
    }

    static void logBodyCache(Context context, long sequence, String stage,
                             long cacheFileBytes, boolean databaseContent,
                             boolean savedWarningPresent) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatBodyCache(sequence, stage, cacheFileBytes,
                databaseContent, savedWarningPresent));
    }

    static String formatBodyCache(long sequence, String stage, long cacheFileBytes,
                                  boolean databaseContent, boolean savedWarningPresent) {
        String safeStage = ("persisted".equals(stage) || "reused".equals(stage)
                ? stage : "unknown");
        return "Zoho REST body cache" +
                " request_seq=" + Math.max(0, sequence) +
                " stage=" + safeStage +
                " cache_file_bytes=" + Math.max(-1, cacheFileBytes) +
                " database_content=" + databaseContent +
                " saved_warning_present=" + savedWarningPresent;
    }

    static void logInlineMapping(Context context, int imageDisplayQueries,
                                 int attachmentInfoMatches, int rawContentIdMatches,
                                 int sourceRewrites) {
        logInlineMapping(context, imageDisplayQueries, attachmentInfoMatches,
                rawContentIdMatches, sourceRewrites, "unspecified");
    }

    static void logInlineMapping(Context context, int imageDisplayQueries,
                                 int attachmentInfoMatches, int rawContentIdMatches,
                                 int sourceRewrites, String renderMode) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatInlineMapping(imageDisplayQueries,
                attachmentInfoMatches, rawContentIdMatches, sourceRewrites, renderMode));
    }

    static String formatInlineMapping(int imageDisplayQueries, int attachmentInfoMatches,
                                      int rawContentIdMatches, int sourceRewrites) {
        return formatInlineMapping(imageDisplayQueries, attachmentInfoMatches,
                rawContentIdMatches, sourceRewrites, "unspecified");
    }

    static String formatInlineMapping(int imageDisplayQueries, int attachmentInfoMatches,
                                      int rawContentIdMatches, int sourceRewrites,
                                      String renderMode) {
        String mode = ("compact".equals(renderMode) ||
                "full_html_pre_relative".equals(renderMode) || "html".equals(renderMode)
                ? renderMode : "unspecified");
        return "Zoho REST ImageDisplay mapping" +
                " render_mode=" + mode +
                " image_display_cid_queries=" + Math.max(0, imageDisplayQueries) +
                " attachmentinfo_content_id_matches=" + Math.max(0, attachmentInfoMatches) +
                " raw_content_id_matches=" + Math.max(0, rawContentIdMatches) +
                " src_rewrites=" + Math.max(0, sourceRewrites);
    }

    static boolean isPlainMultipartFallbackWarning(String warning) {
        return (warning != null && warning.startsWith("Expected text/plain") &&
                warning.contains(" got multipart") && warning.contains(" result=false"));
    }

    static void logExpansion(Context context, long sequence, String stage,
                             String inputKind, String bodyType, String rootType,
                             int attachmentCount, int inlineResources, int inlineImages,
                             int warningCount, long renderedChars) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatExpansion(sequence, stage, inputKind,
                bodyType, rootType, attachmentCount, inlineResources, inlineImages,
                warningCount, renderedChars));
    }

    static String formatExpansion(long sequence, String stage,
                                  String inputKind, String bodyType, String rootType,
                                  int attachmentCount, int inlineResources, int inlineImages,
                                  int warningCount, long renderedChars) {
        String safeStage = ("prepared".equals(stage) || "parts_ready".equals(stage) ||
                "parts_failed".equals(stage) || "body_rendered".equals(stage)
                ? stage : "unknown");
        return "Zoho REST MIME expansion" +
                " request_seq=" + Math.max(0, sequence) +
                " stage=" + safeStage +
                " input=" + safeInputKind(inputKind) +
                " body_type=" + sanitizeMimeType(bodyType) +
                " root_type=" + sanitizeMimeType(rootType) +
                " attachments=" + Math.max(0, attachmentCount) +
                " inline_resources=" + Math.max(0, inlineResources) +
                " inline_images=" + Math.max(0, inlineImages) +
                " warnings=" + Math.max(0, warningCount) +
                " rendered_chars=" + Math.max(-1, renderedChars);
    }

    static void logAttachmentInfo(Context context, long sequence, int ordinaryAttachments,
                                  int inlineResources, int inlineImages) {
        if (!isEnabled(context))
            return;
        EntityLog.logProtocol(context, formatAttachmentInfo(sequence, ordinaryAttachments,
                inlineResources, inlineImages));
    }

    static String formatAttachmentInfo(long sequence, int ordinaryAttachments,
                                       int inlineResources, int inlineImages) {
        return "Zoho REST attachment info" +
                " request_seq=" + Math.max(0, sequence) +
                " ordinary_attachments=" + Math.max(0, ordinaryAttachments) +
                " inline_resources=" + Math.max(0, inlineResources) +
                " inline_images=" + Math.max(0, inlineImages);
    }

    static String sanitizeRoute(String path) {
        if (path == null || path.length() > 2048)
            return "/{route}";
        int query = path.indexOf('?');
        String pathname = (query < 0 ? path : path.substring(0, query));
        String[] raw = pathname.split("/");
        StringBuilder safe = new StringBuilder();
        String previous = "";
        int position = 0;
        for (String segment : raw) {
            if (segment.isEmpty())
                continue;
            if (safe.length() == 0) {
                if (!"api".equals(segment))
                    return "/{route}";
                safe.append('/').append("api");
                previous = "api";
            } else if (position == 1) {
                if (!"accounts".equals(segment))
                    return "/{route}";
                safe.append('/').append("accounts");
                previous = "accounts";
            } else if (position == 2) {
                safe.append("/{account}");
                previous = "{account}";
            } else if ("folders".equals(previous)) {
                safe.append("/{folder}");
                previous = "{folder}";
            } else if ("messages".equals(previous) &&
                    !(position == 4 && ("view".equals(segment) || "attachments".equals(segment)))) {
                safe.append("/{message}");
                previous = "{message}";
            } else if ("attachments".equals(previous)) {
                safe.append("/{attachment}");
                previous = "{attachment}";
            } else if (isRouteWord(segment)) {
                safe.append('/').append(segment);
                previous = segment;
            } else {
                safe.append("/{route}");
                previous = "{route}";
            }
            position++;
        }
        return (position >= 2 ? safe.toString() : "/{route}");
    }

    private static boolean isRouteWord(String value) {
        return ("folders".equals(value) || "messages".equals(value) ||
                "view".equals(value) || "attachments".equals(value) ||
                "attachmentinfo".equals(value) || "header".equals(value) ||
                "content".equals(value) || "originalmessage".equals(value) ||
                "inline".equals(value) || "updatemessage".equals(value) ||
                "emptyFolder".equals(value));
    }

    private static String sanitizeMimeType(String value) {
        if (value == null)
            return "none";
        int parameter = value.indexOf(';');
        String base = (parameter < 0 ? value : value.substring(0, parameter)).trim();
        return (MIME_TYPE.matcher(base).matches() ? base.toLowerCase(Locale.ROOT) : "other");
    }

    private static String safeInputKind(String value) {
        return ("empty".equals(value) || "text".equals(value) || "multipart".equals(value) ||
                "nested".equals(value)
                ? value : "unknown");
    }

    private static String sanitizeMimePath(String value) {
        if (value == null || value.isEmpty() || value.length() > 512)
            return "unknown";
        String[] nodes = value.split(">", -1);
        if (nodes.length == 0 || nodes.length > 33)
            return "unknown";
        StringBuilder safe = new StringBuilder();
        for (String node : nodes) {
            if (!("related".equals(node) || "alternative".equals(node) ||
                    "mixed".equals(node) || "report".equals(node) ||
                    "signed".equals(node) || "encrypted".equals(node) ||
                    "digest".equals(node) || "parallel".equals(node) ||
                    "html".equals(node) || "plain".equals(node) ||
                    "other".equals(node)))
                return "unknown";
            if (safe.length() > 0)
                safe.append('>');
            safe.append(node);
        }
        return safe.toString();
    }

    private static String sanitizeOutcome(String value) {
        return ("success".equals(value) || "http_error".equals(value) ||
                "api_error".equals(value) || "io_error".equals(value) ||
                "parse_error".equals(value) || "auth_retry".equals(value)
                ? value : "unknown");
    }

    private static String sanitizeErrorCode(String value) {
        return (value != null && (value.matches("[1-5][0-9]{2}") ||
                SAFE_ERROR_TOKENS.contains(value)) ? value : null);
    }

    private static String sanitizeErrorDescription(String value) {
        return (value != null && SAFE_ERROR_TOKENS.contains(value) ? value : null);
    }

    private static boolean isEnabled(Context context) {
        return (context != null && PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("protocol", false));
    }
}
