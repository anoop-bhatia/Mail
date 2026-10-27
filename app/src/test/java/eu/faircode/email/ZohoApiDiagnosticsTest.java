package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ZohoApiDiagnosticsTest {
    @Test
    public void zohoRoutesKeepOnlyKnownRouteWordsAndMaskEveryIdentifier() {
        String path = "/api/accounts/account-secret-123/folders/folder@example.invalid" +
                "/messages/message-secret-456/attachments/attachment-secret-789" +
                "?contentId=private-cid&fileName=private-name.pdf";

        String route = ZohoApiDiagnostics.sanitizeRoute(path);

        assertEquals("/api/accounts/{account}/folders/{folder}/messages/{message}" +
                "/attachments/{attachment}", route);
        assertFalse(route.contains("account-secret-123"));
        assertFalse(route.contains("folder@example.invalid"));
        assertFalse(route.contains("message-secret-456"));
        assertFalse(route.contains("attachment-secret-789"));
        assertFalse(route.contains("private-cid"));
        assertFalse(route.contains("private-name.pdf"));
    }

    @Test
    public void inlineDiagnosticsRecordConfiguredHeadersAndCorrelateWithoutExposingIds() {
        String folder = "folder-private-123";
        String message = "message-private-456";
        String cid = "<private-cid@example.invalid>";
        String commit = "0123456789abcdef0123456789abcdef01234567";
        String inlinePath = "/api/accounts/account-private/folders/" + folder +
                "/messages/" + message + "/inline?contentId=private-cid&fileName=secret.jpg";

        String request = ZohoApiDiagnostics.formatInlineRequestContext(9, 1, "GET", inlinePath,
                "*/*", null, ZohoApiDiagnostics.inlineScopeTag(folder, message),
                ZohoApiDiagnostics.inlineCidTag(cid), "1.2337", 2337, commit);
        String content = ZohoApiDiagnostics.formatMessageScope("content", "GET",
                "/api/accounts/account-private/folders/" + folder + "/messages/" + message + "/content",
                folder, message);
        String association = ZohoApiDiagnostics.formatInlineCidAssociation(folder, message, cid,
                true, true, true);

        assertTrue(request.contains("configured_accept=*/* configured_content_type=none"));
        assertTrue(request.contains("route=/api/accounts/{account}/folders/{folder}/messages/{message}/inline"));
        assertTrue(request.contains("app_version=1.2337 version_code=2337 source_commit=" + commit));
        assertTrue(content.contains("route=/api/accounts/{account}/folders/{folder}/messages/{message}/content"));
        assertTrue(content.contains("scope_tag=" + ZohoApiDiagnostics.inlineScopeTag(folder, message)));
        assertTrue(association.contains("cid_tag=" + ZohoApiDiagnostics.inlineCidTag(cid)));
        assertTrue(association.contains("cid_row_present=true inline_image_row=true source_matches_row=true"));
        for (String value : new String[]{folder, message, cid, "account-private", "secret.jpg"}) {
            assertFalse(request.contains(value));
            assertFalse(content.contains(value));
            assertFalse(association.contains(value));
        }
        assertFalse(request.contains("Authorization"));
        assertFalse(request.contains("access-token"));
    }

    @Test
    public void inline406CorrelationRecordsSelectedMetadataRowAndOnlyConfiguredHeaders() {
        String body = "{\"requestId\":\"69d08297-8a87-45cb-a728-e5895f33da95\"," +
                "\"status\":{\"code\":406,\"description\":\"Not Acceptable\"}," +
                "\"error\":{\"errorCode\":\"NOT_ACCEPTABLE\"," +
                "\"errorMessage\":\"The requested media type is not supported. Check the Accept header.\"," +
                "\"location\":\"Accept\",\"locationType\":\"header\"}}";
        String path = "/api/accounts/acct%2F19/folders/folder%2F6/messages/msg%2F42/inline" +
                "?contentId=logo%40example.test&fileName=ignored.png";
        ZohoApiDiagnostics.InlineRequestCorrelation row =
                new ZohoApiDiagnostics.InlineRequestCorrelation("acct/19", "folder/6", "msg/42",
                        "logo@example.test", 987L, 123L, "remote-inline-77", "folder/6",
                        "<logo@example.test>");

        String entry = ZohoApiDiagnostics.formatInline406Correlation(17, 1, "GET", path,
                406, 282, "application/json", body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, body, "complete",
                "*/*", null, row);

        assertTrue(entry.contains("Zoho REST inline 406 correlation seq=17 attempt=1"));
        assertTrue(entry.contains("route=/api/accounts/{account}/folders/{folder}/messages/{message}/inline"));
        assertTrue(entry.contains("account_id=\"acct/19\" folder_id=\"folder/6\" message_id=\"msg/42\""));
        assertTrue(entry.contains("request_content_id=\"logo@example.test\""));
        assertTrue(entry.contains("metadata_attachment_row_id=987 metadata_message_row_id=123"));
        assertTrue(entry.contains("metadata_provider_id=\"remote-inline-77\""));
        assertTrue(entry.contains("metadata_provider_folder=\"folder/6\""));
        assertTrue(entry.contains("metadata_content_id=\"<logo@example.test>\""));
        assertTrue(entry.contains("metadata_cid_matches_request=true"));
        assertTrue(entry.contains("metadata_folder_matches_request=true"));
        assertTrue(entry.contains("configured_accept=*/* configured_content_type=none"));
        assertTrue(entry.contains("response_type=application/json response_bytes="));
        assertTrue(entry.contains("request_id=\"69d08297-8a87-45cb-a728-e5895f33da95\""));
        assertTrue(entry.contains("response_status_code=406 response_status_description=\"Not Acceptable\""));
        assertTrue(entry.contains("error_code=NOT_ACCEPTABLE"));
        assertTrue(entry.contains("error_message=\"The requested media type is not supported. Check the Accept header.\""));
        assertTrue(entry.contains("error_location=\"Accept\" error_location_type=\"header\""));
        assertFalse(entry.contains("fileName"));
        assertFalse(entry.contains("Authorization"));
        assertFalse(entry.contains("oauth-token"));
        assertFalse(entry.contains("message body"));
    }

    @Test
    public void exactInlineCorrelationIsSuppressedForAnythingButCompletedGet406InlineRoute() {
        ZohoApiDiagnostics.InlineRequestCorrelation row =
                new ZohoApiDiagnostics.InlineRequestCorrelation("account", "folder", "message",
                        "cid@example.test", 1L, 2L, "provider-id", "folder", "<cid@example.test>");
        String path = "/api/accounts/a/folders/f/messages/m/inline?contentId=cid";
        String body = "{\"error\":{\"errorCode\":\"NOT_ACCEPTABLE\"}}";

        assertNull(ZohoApiDiagnostics.formatInline406Correlation(1, 1, "GET", path,
                200, 1, "application/json", 0, 0, body, "complete",
                "*/*", null, row));
        assertNull(ZohoApiDiagnostics.formatInline406Correlation(1, 1, "POST", path,
                406, 1, "application/json", 0, 0, body, "complete",
                "*/*", null, row));
        assertNull(ZohoApiDiagnostics.formatInline406Correlation(1, 1, "GET",
                "/api/accounts/a/folders/f/messages/m/attachments/file?contentId=cid",
                406, 1, "application/json", 0, 0, body, "complete",
                "*/*", null, row));
        assertNull(ZohoApiDiagnostics.formatInline406Correlation(1, 1, "GET", path,
                406, 1, "application/json", 0, 0, body, "read_error",
                "*/*", null, row));
    }

    @Test
    public void invalidHeadersAndBuildIdentityAreNeverCopiedIntoDiagnosticText() {
        String output = ZohoApiDiagnostics.formatInlineRequestContext(1, 1, "GET",
                "/api/accounts/a/folders/f/messages/m/inline?contentId=cid",
                "Zoho-oauthtoken secret-value", "password-value", "raw-folder", "raw-cid",
                "version with private text", 2337, "not-a-commit");

        assertTrue(output.contains("configured_accept=other configured_content_type=other"));
        assertTrue(output.contains("scope_tag=unknown cid_tag=unknown"));
        assertTrue(output.contains("app_version=unknown version_code=2337 source_commit=unknown"));
        assertFalse(output.contains("secret-value"));
        assertFalse(output.contains("password-value"));
        assertFalse(output.contains("raw-folder"));
        assertFalse(output.contains("raw-cid"));
        assertFalse(output.contains("Authorization"));
    }

    @Test
    public void exchangeEntryContainsUsefulProtocolMetadataButNoPayloadOrCredentials() {
        String entry = ZohoApiDiagnostics.formatExchange(17, "get",
                "/api/accounts/private-account/folders/private-folder/messages/private-message/content" +
                        "?includeBlockContent=true&email=user@example.invalid",
                1, 200, 29, "application/json; charset=UTF-8", 128,
                "application/json; charset=UTF-8", 1042, "success");

        assertTrue(entry.contains("Zoho REST exchange seq=17"));
        assertTrue(entry.contains("method=GET"));
        assertTrue(entry.contains("route=/api/accounts/{account}/folders/{folder}" +
                "/messages/{message}/content"));
        assertTrue(entry.contains("status=200"));
        assertTrue(entry.contains("elapsed_ms=29"));
        assertTrue(entry.contains("request_type=application/json"));
        assertTrue(entry.contains("response_type=application/json"));
        assertTrue(entry.contains("outcome=success"));
        assertFalse(entry.contains("private-account"));
        assertFalse(entry.contains("private-folder"));
        assertFalse(entry.contains("private-message"));
        assertFalse(entry.contains("user@example.invalid"));
        assertFalse(entry.contains("Zoho-oauthtoken"));
        assertFalse(entry.contains("message body"));
    }

    @Test
    public void contentAndMultipartClassifiersReturnOnlyBroadInputKinds() {
        assertEquals("text", ZohoContentNormalizer.sourceKind("<div>private body</div>"));
        assertEquals("multipart", ZohoContentNormalizer.sourceKind(
                "Content-Type: multipart/mixed; boundary=private-boundary\r\n\r\nprivate body"));
        assertEquals("empty", ZohoContentNormalizer.sourceKind(null));
    }

    @Test
    public void normalizationEntryDistinguishesNestedTopologyAndCountsWithoutValues() {
        String entry = ZohoApiDiagnostics.formatNormalization(21, "multipart", 8192,
                "text/html; charset=UTF-8", 512, true, "related>alternative>html",
                1, 1, 2, 2, 2);

        assertTrue(entry.contains("source_path=related>alternative>html"));
        assertTrue(entry.contains("normalizer_output_len=512 normalizer_output_is_html=true"));
        assertTrue(entry.contains("text_plain_parts=1"));
        assertTrue(entry.contains("text_html_parts=1"));
        assertTrue(entry.contains("inline_resources=2"));
        assertTrue(entry.contains("inline_images=2"));
        assertTrue(entry.contains("cid_refs=2"));
        assertFalse(entry.contains("private boundary"));
        assertFalse(entry.contains("fixture.invalid"));
        assertFalse(entry.contains("HTML_BODY_SENTINEL"));

        String nestedEntry = ZohoApiDiagnostics.formatNormalization(23, "nested", 1200,
                "text/html", 80, true, "mixed>related>html", 0, 1, 1, 1, 1);
        assertTrue(nestedEntry.contains("input=nested"));

        String malformedPath = ZohoApiDiagnostics.formatNormalization(22, "multipart", 10,
                "text/html", 4, true, "related>private-id@example.invalid", 1, 1, 0, 0, 0);
        assertTrue(malformedPath.contains("source_path=unknown"));
        assertFalse(malformedPath.contains("private-id@example.invalid"));
    }

    @Test
    public void expansionAndAttachmentInfoExposeInlineResourceCountsSeparately() {
        String expansion = ZohoApiDiagnostics.formatExpansion(21, "body_rendered", "multipart",
                "text/html", "multipart/mixed", 3, 2, 2, 0, 512);
        String info = ZohoApiDiagnostics.formatAttachmentInfo(19, 1, 2, 2);

        assertTrue(expansion.contains("attachments=3 inline_resources=2 inline_images=2"));
        assertTrue(info.contains("ordinary_attachments=1 inline_resources=2 inline_images=2"));
    }

    @Test
    public void bodyStageDiagnosticsExposeOnlyCountsAndLengthParity() {
        String fetch = ZohoApiDiagnostics.formatBodyFetch(31, 4096, 4096, 2050);
        String mismatch = ZohoApiDiagnostics.formatBodyFetch(32, 4096, 4000, 2050);
        String structure = ZohoApiDiagnostics.formatBodyStructure(
                31, "normalizer_input", true, 2, 1);
        String renderer = ZohoApiDiagnostics.formatRenderer(31, false, false, true, 1);
        String warnings = ZohoApiDiagnostics.formatWarningSources(
                31, "rendered", 2, 1, true);
        String cache = ZohoApiDiagnostics.formatBodyCache(31, "persisted", 2048, true, true);
        String mapping = ZohoApiDiagnostics.formatInlineMapping(2, 2, 2, 1);

        assertTrue(fetch.contains("content_length_header=4096 actual_len=4096"));
        assertTrue(fetch.contains("length_check=match"));
        assertTrue(mismatch.contains("length_check=mismatch"));
        assertTrue(structure.contains("stage=normalizer_input has_boundary=true"));
        assertTrue(structure.contains("has_cid_query=true image_display_cid_count=2 cid_refs=1"));
        assertTrue(renderer.contains("renderer_input_is_html=false renderer_input_is_escaped=true"));
        assertTrue(renderer.contains("renderer_is_html=false renderer_xplain=true"));
        assertTrue(renderer.contains("xplain_reason=multipart_plain_fallback"));
        assertTrue(warnings.contains("generated_warnings=2"));
        assertTrue(warnings.contains("generated_plain_multipart_fallbacks=1 saved_warning_present=true"));
        assertTrue(cache.contains("stage=persisted cache_file_bytes=2048 database_content=true"));
        assertTrue(cache.contains("saved_warning_present=true"));
        assertFalse(fetch.contains("private body"));
        assertFalse(structure.contains("private-boundary"));
        assertFalse(renderer.contains("fixture.invalid"));
        assertFalse(warnings.contains("account"));
        assertFalse(cache.contains("content hash"));
        assertTrue(mapping.contains("image_display_cid_queries=2 attachmentinfo_content_id_matches=2"));
        assertTrue(mapping.contains("raw_content_id_matches=2 src_rewrites=1"));
        assertFalse(mapping.contains("private-cid"));
    }

    @Test
    public void plainMultipartFallbackClassifierDoesNotReturnWarningText() {
        assertTrue(ZohoApiDiagnostics.isPlainMultipartFallbackWarning(
                "Expected text/plain; charset=UTF-8 got multipart [image/png] result=false"));
        assertFalse(ZohoApiDiagnostics.isPlainMultipartFallbackWarning(
                "Expected text/html got multipart result=true"));
    }

    @Test
    public void binaryExchangeRecordsOnlyRedactedRouteAndByteCount() {
        String entry = ZohoApiDiagnostics.formatBinaryExchange(8, "GET",
                "/api/accounts/private-account/folders/private-folder/messages/private-message" +
                        "/inline?contentId=private-cid&fileName=private-name.png",
                1, 200, 43, "image/png", 2048, "success", null, null);

        assertTrue(entry.contains("Zoho REST binary exchange seq=8"));
        assertTrue(entry.contains("route=/api/accounts/{account}/folders/{folder}" +
                "/messages/{message}/inline"));
        assertTrue(entry.contains("response_type=image/png"));
        assertTrue(entry.contains("response_bytes=2048"));
        assertFalse(entry.contains("private-account"));
        assertFalse(entry.contains("private-folder"));
        assertFalse(entry.contains("private-message"));
        assertFalse(entry.contains("private-cid"));
        assertFalse(entry.contains("private-name.png"));

        String failure = ZohoApiDiagnostics.formatBinaryExchange(9, "GET",
                "/api/accounts/private-account/folders/private-folder/messages/private-message" +
                        "/inline?contentId=private-cid&fileName=private-name.png",
                1, 406, 43, "application/json", -1, "http_error",
                "406", "NOT_ACCEPTABLE");
        assertTrue(failure.contains("error_code=406 error_description=NOT_ACCEPTABLE"));
        assertFalse(failure.contains("private-account"));
        assertFalse(failure.contains("private-folder"));
        assertFalse(failure.contains("private-message"));
        assertFalse(failure.contains("private-cid"));
        assertFalse(failure.contains("private-name.png"));

        String unsafe = ZohoApiDiagnostics.formatBinaryExchange(10, "GET", "/api/accounts/a/folders/f/messages/m/inline",
                1, 406, 43, "application/json", -1, "http_error",
                "406 private@example.test", "Not acceptable: token-secret");
        assertFalse(unsafe.contains("private@example.test"));
        assertFalse(unsafe.contains("token-secret"));
        assertFalse(unsafe.contains("error_code="));
        assertFalse(unsafe.contains("error_description="));
    }

    @Test
    public void inline406DiagnosticsRecordJsonShapeAndCompleteRawProviderBody() {
        String body = "{\"error\":{\"errorCode\":\"NOT_ACCEPTABLE\"," +
                "\"errorMessage\":\"private@example.test provider diagnostic\"," +
                "\"location\":\"/api/accounts/acct-private/folders/folder-private/" +
                "messages/message-private/inline\",\"locationType\":\"path\"," +
                "\"requestId\":\"a1b2c3d4e5f60718293a4b5c\"}," +
                "\"status\":{\"code\":406,\"description\":\"Not Acceptable\"}}";
        long bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String entry = ZohoApiDiagnostics.formatInlineHttpErrorExchange(12, "GET",
                "/api/accounts/private-account/folders/private-folder/messages/private-message/inline" +
                        "?contentId=private-cid&fileName=private-name.png",
                1, 406, 19, "application/json", bytes, bytes, body, "complete");

        assertTrue(entry.contains("response_bytes=" + bytes));
        assertTrue(entry.contains("content_length_header=" + bytes + " length_check=match"));
        assertTrue(entry.contains("error_code=406 error_description=NOT_ACCEPTABLE"));
        assertTrue(entry.contains("error_body_state=json"));
        assertTrue(entry.contains("error.errorMessage:string"));
        assertTrue(entry.contains("status.description:string"));
        assertTrue(entry.contains("error_body_raw=" + org.json.JSONObject.quote(body)));
        assertTrue(entry.contains("private@example.test"));
        assertTrue(entry.contains("acct-private"));
        assertTrue(entry.contains("folder-private"));
        assertTrue(entry.contains("message-private"));
        assertFalse(entry.contains("Authorization:"));
        assertFalse(entry.contains("access-token-fixture"));
        assertFalse(entry.contains("message-body-fixture"));
    }

    @Test
    public void inlineErrorDiagnosticsRecordCompleteEmptyNonJsonAndMalformedBodies() {
        String empty = ZohoApiDiagnostics.formatInlineHttpErrorExchange(13, "GET",
                "/api/accounts/a/folders/f/messages/m/inline?contentId=cid", 1, 406,
                4, "application/json", 0, 0, "", "empty");
        assertTrue(empty.contains("response_bytes=0"));
        assertTrue(empty.contains("content_length_header=0 length_check=match"));
        assertTrue(empty.contains("error_body_state=empty json_fields=none"));
        assertTrue(empty.contains("error_body_raw=\"\""));
        assertFalse(empty.contains("error_code="));

        String body = "private@example.test opaque-account-value";
        long bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String nonJson = ZohoApiDiagnostics.formatInlineHttpErrorExchange(14, "GET",
                "/api/accounts/a/folders/f/messages/m/inline?contentId=cid", 1, 406,
                5, "application/json", bytes, bytes + 3, body, "complete");
        assertTrue(nonJson.contains("response_bytes=" + bytes));
        assertTrue(nonJson.contains("length_check=mismatch"));
        assertTrue(nonJson.contains("error_body_state=invalid_json json_fields=none"));
        assertTrue(nonJson.contains("error_body_raw=" + org.json.JSONObject.quote(body)));
        assertTrue(nonJson.contains("private@example.test"));
        assertTrue(nonJson.contains("opaque-account-value"));
        assertFalse(nonJson.contains("error_code="));

        String malformedBody = "{\"error\":{\"errorCode\":\"INVALID_DATA\"";
        String malformedJson = ZohoApiDiagnostics.formatInlineHttpErrorExchange(44, "GET",
                "/api/accounts/a/folders/f/messages/m/inline?contentId=cid", 1, 406, 5,
                "application/json", malformedBody.length(), malformedBody.length(),
                malformedBody, "complete");
        assertTrue(malformedJson.contains("error_body_state=invalid_json json_fields=none"));
        assertTrue(malformedJson.contains("error_body_raw=" +
                org.json.JSONObject.quote(malformedBody)));
        assertTrue(malformedJson.contains("INVALID_DATA"));

        String oversizedBody = "{\"error\":{\"errorCode\":\"INVALID_DATA\"}}";
        String oversized = ZohoApiDiagnostics.formatInlineHttpErrorExchange(45, "GET",
                "/api/accounts/a/folders/f/messages/m/inline?contentId=cid", 1, 406, 5,
                "application/json", 10000, 10000, oversizedBody, "oversized");
        assertTrue(oversized.contains("error_body_state=oversized json_fields=none"));
        assertFalse(oversized.contains("error_body_raw="));
        assertFalse(oversized.contains("INVALID_DATA"));
    }

    @Test
    public void rawProviderBodyIs406AndExactInlineRouteOnly() {
        String body = "raw provider diagnostic body";
        String[] paths = new String[]{
                "/api/accounts/a/folders/f/messages/m/inline",
                "/api/accounts/a/folders/f/messages/m/attachments?contentId=cid"
        };
        for (String path : paths) {
            String entry = ZohoApiDiagnostics.formatInlineHttpErrorExchange(46, "GET", path,
                    1, 406, 5, "application/json", body.length(), body.length(), body, "complete");
            assertFalse(entry.contains("error_body_raw="));
            assertFalse(entry.contains(body));
        }

        String non406 = ZohoApiDiagnostics.formatInlineHttpErrorExchange(47, "GET",
                "/api/accounts/a/folders/f/messages/m/inline?contentId=cid", 1, 500, 5,
                "application/json", body.length(), body.length(), body, "complete");
        assertFalse(non406.contains("error_body_raw="));
        assertFalse(non406.contains(body));

        String nonGet = ZohoApiDiagnostics.formatInlineHttpErrorExchange(48, "POST",
                "/api/accounts/a/folders/f/messages/m/inline?contentId=cid", 1, 406, 5,
                "application/json", body.length(), body.length(), body, "complete");
        assertFalse(nonGet.contains("error_body_raw="));
        assertFalse(nonGet.contains(body));
    }

    @Test
    public void routeWordValuesStillGetMaskedWhenTheyOccupyIdentifierPositions() {
        assertEquals("/api/accounts/{account}/folders/{folder}/messages/{message}" +
                        "/attachments/{attachment}",
                ZohoApiDiagnostics.sanitizeRoute(
                        "/api/accounts/messages/folders/content/messages/attachments/attachments/content"));
    }

    @Test
    public void importTraceCorrelatesTransportAndSafeMimeStructure() {
        String trace = "b29a69da-24f2-4ff7-9f12-1c8bced87b60";
        String exchange = ZohoApiDiagnostics.formatExchange(17, "get",
                "/api/accounts/private-account/folders/private-folder/messages/private-message/content",
                1, 200, 29, null, -1, "application/json", 1042, "success", trace);
        String safeType = ZohoApiDiagnostics.safeContentType(
                "multipart/related; boundary=\"private-boundary\"; charset=UTF-8; " +
                        "name=\"private-file.pdf\"; start=\"<private-cid@example.invalid>\"");

        assertTrue(exchange.contains("trace=" + trace));
        assertTrue(exchange.contains("route=/api/accounts/{account}/folders/{folder}" +
                "/messages/{message}/content"));
        assertTrue(safeType.startsWith("multipart/related; boundary=<set>; charset=UTF-8"));
        assertTrue(safeType.contains("name=<omitted>"));
        assertTrue(safeType.contains("start=<omitted>"));
        assertFalse(safeType.contains("private-boundary"));
        assertFalse(safeType.contains("private-file.pdf"));
        assertFalse(safeType.contains("private-cid@example.invalid"));
    }
}
