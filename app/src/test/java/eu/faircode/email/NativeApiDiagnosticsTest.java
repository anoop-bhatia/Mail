package eu.faircode.email;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeApiDiagnosticsTest {
    @Test
    public void graphProtocolOutputRedactsIdsQueryValuesAndSensitiveMessageText() {
        String url = "https://graph.microsoft.com/v1.0/users/alice%40example.com/" +
                "mailFolders/folder-opaque-123/messages/AAMkAGVmMDEzMTM4LTZmYWUtNDdkNC1hMDZi/attachments" +
                "?$select=id,name&$skiptoken=private-cursor-value";
        String error = "{\"error\":{\"code\":\"BadRequest\",\"message\":" +
                "\"The request is invalid. subject:Quarterly acquisition alice@example.com " +
                "https://private.example/item\",\"innerError\":{\"code\":\"ErrorInvalidRequest\"}}}";

        String result = NativeApiDiagnostics.formatGraph("get", url, 400,
                error.getBytes(StandardCharsets.UTF_8));

        assertTrue(result.contains("Graph method=GET"));
        assertTrue(result.contains("route=/v1.0/users/{id}/mailFolders/{id}/messages/{id}/attachments"));
        assertTrue(result.contains("query=[$select, $skiptoken]"));
        assertTrue(result.contains("status=400"));
        assertTrue(result.contains("error.code=BadRequest innerError.code=ErrorInvalidRequest"));
        assertTrue("Sanitized output: " + result,
                result.contains("error.message=The request is invalid."));
        assertFalse(result.contains("alice"));
        assertFalse(result.contains("folder-opaque-123"));
        assertFalse(result.contains("AAMkAGVm"));
        assertFalse(result.contains("private-cursor-value"));
        assertFalse(result.contains("Quarterly acquisition"));
        assertFalse(result.contains("private.example"));
    }

    @Test
    public void graphHttpTraceIsCorrelatedWithoutExposingTheRequestPathIdentifier() {
        String trace = "1b5c6308-7e3a-4ccd-992a-d75b953663f1";
        String result = NativeApiDiagnostics.formatGraph("GET",
                "https://graph.microsoft.com/v1.0/me/messages/opaque-message-id?$select=id,body",
                200, null, trace);
        assertTrue(result.contains("trace=" + trace));
        assertTrue(result.contains("route=/v1.0/me/messages/{id}"));
        assertFalse(result.contains("opaque-message-id"));
        assertFalse(result.contains("body text"));

        String invalid = NativeApiDiagnostics.formatGraph("GET",
                "https://graph.microsoft.com/v1.0/me/messages/opaque-message-id", 200,
                null, "private-message-id");
        assertFalse(invalid.contains("private-message-id"));
        assertFalse(invalid.contains("trace="));
    }

    @Test
    public void unexpectedQueryNamesAreCollapsedWithoutValues() {
        String result = NativeApiDiagnostics.formatGraph("GET",
                "https://graph.microsoft.com/v1.0/me/messages/opaque-id?$select=id" +
                        "&alice%40example.com=private-query-value", 200, null);

        assertTrue(result.contains("query=[$select, {param}]"));
        assertFalse(result.contains("alice"));
        assertFalse(result.contains("private-query-value"));
    }

    @Test
    public void graphErrorCodeFieldsDoNotExposeOpaqueIdentifiers() {
        String error = "{\"error\":{\"code\":\"opaque-message-id\",\"message\":" +
                "\"Invalid request\",\"innerError\":{\"code\":\"AAMkAGVmMDEzMTM4\"}}}";

        String result = NativeApiDiagnostics.formatGraph("GET",
                "https://graph.microsoft.com/v1.0/me/messages/message-id/attachments", 400,
                error.getBytes(StandardCharsets.UTF_8));

        assertFalse(result.contains("opaque-message-id"));
        assertFalse(result.contains("AAMkAGVmMDEzMTM4"));
        assertFalse(result.contains("error.code="));
        assertFalse(result.contains("innerError.code="));
    }

    @Test
    public void nativeProviderBodyFieldsContainOnlySafeCategoriesAndLengths() {
        assertEquals("Native provider=zoho operation=body event=complete success=true " +
                        "source_length=327 rendered_chars=246 cache_reused=false",
                NativeApiDiagnostics.formatBody("zoho", true, 327, 246, false));
        assertEquals("Native provider=unknown operation=body event=complete success=false " +
                        "source_length=-1 rendered_chars=-1 cache_reused=false",
                NativeApiDiagnostics.formatBody("unexpected-provider", false, -1, -1, false));
    }

    @Test
    public void bodyOpenAndWorkerDiagnosticsUseOnlySafeStateFields() {
        String open = NativeApiDiagnostics.formatBodyOpenDecision("gmail", "queued",
                true, false, true, false);
        assertEquals("Native provider=gmail operation=body event=open decision=queued " +
                        "folder_download=true content=false provider_id_present=true operation_pending=false",
                open);
        assertEquals("Native provider=gmail operation=body event=started",
                NativeApiDiagnostics.formatContentOperationEvent("gmail", "body", "started"));
        assertEquals("Native provider=zoho operation=attachment event=queued",
                NativeApiDiagnostics.formatContentOperationEvent("zoho", "attachment", "queued"));
        assertEquals("Native provider=gmail operation=body event=cache_miss queued=true",
                NativeApiDiagnostics.formatBodyCacheMiss("gmail", true));
        assertFalse(open.contains("provider-message-id"));
    }

    @Test
    public void contentFailureDiagnosticsExposeHttpStatusButNeverExceptionText() {
        String failure = NativeApiDiagnostics.formatContentOperationFailure("gmail", "body",
                new GmailApi.ApiException(429, "private body and bearer token"));
        assertEquals("Native provider=gmail operation=body event=failed failure=http status=429",
                failure);
        assertFalse(failure.contains("private"));
        assertFalse(failure.contains("token"));
        assertEquals("Native provider=graph operation=attachment event=complete success=true bytes=9 cache_hit=false",
                NativeApiDiagnostics.formatAttachmentComplete("graph", 9, false));
        assertEquals("Native provider=unknown operation=body event=failed failure=io status=-1",
                NativeApiDiagnostics.formatContentOperationFailure("unexpected", "body",
                        new java.io.IOException("message body content")));
    }

    @Test
    public void gmailCidLifecycleFieldsAreNumericAndContentFree() {
        assertEquals("Native provider=gmail operation=cid_attachment event=queued " +
                        "success=true count=2",
                NativeApiDiagnostics.formatGmailCid("queued", true, 2, -1, false));
        assertEquals("Native provider=gmail operation=cid_attachment event=worker-started " +
                        "success=true count=1",
                NativeApiDiagnostics.formatGmailCid("worker-started", true, 1, -1, false));
        assertEquals("Native provider=gmail operation=cid_attachment event=fetch-complete " +
                        "success=true count=1 bytes=73 cache_hit=false",
                NativeApiDiagnostics.formatGmailCid("fetch-complete", true, 1, 73, false));
        assertEquals("Native provider=gmail operation=cid_attachment event=redraw " +
                        "success=true available=1",
                NativeApiDiagnostics.formatGmailCidRedraw(1));
        assertFalse(NativeApiDiagnostics.formatGmailCid("unsafe-private-value", true, 1, 0, false)
                .contains("unsafe-private-value"));
    }

    @Test
    public void gmailMimeAndAttachmentImportDiagnosticsExposeOnlySafeCounts() {
        String mime = NativeApiDiagnostics.formatGmailMime("import", false,
                2, 1, 1, 3, 2, 1);
        assertEquals("Native provider=gmail operation=mime event=body_parse stage=import " +
                        "body_complete=false text_parts=2 incomplete_text_parts=1 fetched_text_parts=1 " +
                        "attachment_parts=3 attachment_id_parts=2 inline_data_attachment_parts=1",
                mime);
        assertEquals("Native provider=gmail operation=mime event=body_parse stage=unknown " +
                        "body_complete=true text_parts=0 incomplete_text_parts=0 fetched_text_parts=0 " +
                        "attachment_parts=0 attachment_id_parts=0 inline_data_attachment_parts=0",
                NativeApiDiagnostics.formatGmailMime("private-stage", true,
                        -1, -1, -1, -1, -1, -1));
        assertEquals("Native provider=gmail operation=attachment_import event=complete " +
                        "parsed_parts=4 mime_candidates=0 remote_candidates=4 fallback_rows=4 rows_inserted=4",
                NativeApiDiagnostics.formatGmailAttachmentImport(4, 0, 4, 4, 4));
        assertFalse(mime.contains("subject"));
        assertFalse(mime.contains("gmail-message-id"));
    }
}
