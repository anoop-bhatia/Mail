package eu.faircode.email;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApiMutationLogTest {
    @Test
    public void graphAndZohoEventsAreProviderTaggedAndLowCardinality() {
        assertEquals("Microsoft Graph mutation stage=worker_start provider=graph operation=move result=started",
                ApiMutationLog.formatEvent("graph", "worker_start", EntityOperation.MOVE, "started"));
        assertEquals("Zoho Mail mutation stage=local_commit provider=zoho operation=empty_trash result=success",
                ApiMutationLog.formatEvent("zoho", "local_commit", EntityOperation.ZOHO_PURGE_TRASH,
                        "success"));
        assertEquals("Zoho Mail mutation stage=local_commit provider=zoho operation=empty_spam result=success",
                ApiMutationLog.formatEvent("zoho", "local_commit", EntityOperation.ZOHO_PURGE_SPAM,
                        "success"));
        assertEquals("Microsoft Graph mutation stage=refresh_summary provider=graph operation=refresh result=scheduled",
                ApiMutationLog.formatEvent("graph", "refresh_summary", "refresh", "scheduled"));
        assertEquals("Microsoft Graph mutation stage=picker_callback provider=graph operation=move " +
                        "target_type=label result=invalid_target",
                ApiMutationLog.formatCallback("graph", EntityOperation.MOVE, "label", "invalid_target"));
    }

    @Test
    public void httpStatusAndErrorClassAreAllowlistedWithoutResponseText() {
        String event = ApiMutationLog.formatHttp("zoho", EntityOperation.MOVE, 503,
                "failure", "api");
        assertEquals("Zoho Mail mutation stage=http provider=zoho operation=move status=503 " +
                "result=failure error_class=api", event);

        String unsafe = ApiMutationLog.formatHttp("zoho-account-731", "url:/private/subject",
                9001, "server body with address@example.test", "AuthorizationHeader");
        assertEquals("Native REST mutation stage=http provider=other operation=other status=-1 " +
                "result=other error_class=other", unsafe);
        assertFalse(unsafe.contains("731"));
        assertFalse(unsafe.contains("private"));
        assertFalse(unsafe.contains("address@example.test"));
    }

    @Test
    public void failureSummaryKeepsOnlyCoarseExceptionClass() {
        String event = ApiMutationLog.formatFailure("graph", "failure_summary", EntityOperation.MOVE,
                "unknown", new IOException("Bearer secret-value for private@example.test"));
        assertEquals("Microsoft Graph mutation stage=failure_summary provider=graph operation=move " +
                "result=unknown error_class=io", event);
        assertFalse(event.contains("secret-value"));
        assertFalse(event.contains("private@example.test"));
    }

    @Test
    public void protocolPreferenceAloneControlsTheOnlyLoggerSink() {
        final List<String> protocol = new ArrayList<>();
        ApiMutationLog.ProtocolSink sink = new ApiMutationLog.ProtocolSink() {
            @Override
            public void write(String event) {
                protocol.add(event);
            }
        };
        String event = ApiMutationLog.formatEvent("graph", "durable_queue", EntityOperation.MOVE,
                "observed");

        // Protocol routing has no log-level argument; WARN (log_level=5) cannot suppress this row.
        ApiMutationLog.route(event, true, sink);
        assertEquals(Arrays.asList(event), protocol);

        protocol.clear();
        ApiMutationLog.route(event, false, sink);
        assertTrue(protocol.isEmpty());
    }

    @Test
    public void traceIdsAndProviderStateAreCorrelatedWithoutPrivateIdentifiers() {
        String trace = ApiMutationLog.newTraceId();
        assertEquals(trace, ApiMutationLog.safeTraceId(trace));
        assertEquals(null, ApiMutationLog.safeTraceId("subject alice@example.test"));
        assertEquals(trace, ApiMutationLog.traceFromArgs("[true,true,\"" + trace + "\"]"));
        assertEquals(trace, ApiMutationLog.traceFromArgs(
                "[123,false,null,false,false,\"" + trace + "\"]"));

        String zoho = ApiMutationLog.formatZohoDeleteState("worker_validation", "not_writable",
                trace, false, true, true, true, false);
        assertTrue(zoho.contains("operation=permanent_delete result=not_writable"));
        assertTrue(zoho.contains("trace=" + trace));
        assertTrue(zoho.contains("folder_read_only=false folder_selectable=true"));
        assertTrue(zoho.contains("source_membership_present=false"));
        assertFalse(zoho.contains("alice@example.test"));

        String graph = ApiMutationLog.formatGraphOpen(trace, EntityFolder.INBOX,
                true, true, true, true);
        assertTrue(graph.contains("stage=message_open provider=graph operation=open result=opened"));
        assertTrue(graph.contains("trace=" + trace));
        assertTrue(graph.contains("folder_type=inbox unread=true auto_seen=true"));
        assertTrue(graph.contains("body_missing=true auto_classify_source=true"));
        assertFalse(graph.contains("message-id"));
        String navigation = ApiMutationLog.formatEvent("graph", "navigation", "navigate",
                "auto_closed", trace);
        assertTrue(navigation.contains("stage=navigation provider=graph operation=navigate result=auto_closed"));
        assertTrue(navigation.contains("trace=" + trace));

        String classification = ApiMutationLog.formatGraphClassification(trace, true, false, true);
        assertTrue(classification.contains("stage=classification provider=graph operation=classify result=move_queued"));
        assertTrue(classification.contains("trace=" + trace));
        assertTrue(classification.contains("auto_classify_source=true move_pending_before=false move_pending_after=true"));
        String body = ApiMutationLog.formatGraphBodyResult(trace, true, 246);
        assertTrue(body.contains("stage=body_fetch provider=graph operation=body result=success"));
        assertTrue(body.contains("trace=" + trace));
        assertTrue(body.contains("body_present=true body_chars=246"));
        assertFalse(body.contains("message body"));

        String direct = ApiMutationLog.formatZohoDeleteRequest(true, true, true, trace);
        assertTrue(direct.contains("operation=permanent_delete result=started"));
        assertTrue(direct.contains("expunge=true folder_id_present=true message_id_present=true"));
        assertTrue(direct.contains("trace=" + trace));
        String recoverable = ApiMutationLog.formatZohoDeleteRequest(false, true, true, trace);
        assertTrue(recoverable.contains("operation=delete result=started"));
        assertTrue(recoverable.contains("trace=" + trace));
        assertTrue(recoverable.contains("expunge=false folder_id_present=true message_id_present=true"));
        String reappeared = ApiMutationLog.formatEvent("zoho", "readback_reconcile",
                EntityOperation.ZOHO_PERMANENT_DELETE, "reappeared", trace);
        assertTrue(reappeared.contains("stage=readback_reconcile provider=zoho"));
        assertTrue(reappeared.contains("operation=permanent_delete result=reappeared"));
        assertTrue(reappeared.contains("trace=" + trace));
        String menu = ApiMutationLog.formatZohoMenuGate(trace, true, true,
                false, true, false);
        assertTrue(menu.contains("result=visible_enabled trace=" + trace));
        assertTrue(menu.contains("menu_visible=true menu_enabled=true"));
        assertTrue(menu.contains("folder_read_only=false message_id_present=true trash_or_spam=false"));
    }
}
