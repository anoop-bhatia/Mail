package eu.faircode.email;

import org.json.JSONArray;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GmailMutationLogTest {
    @Test
    public void everyCallbackOutcomeMapsToAnAllowlistedLowCardinalityReason() {
        String[] outcomes = {
                "accepted", "queued", "stored", "source_missing", "invalid_target",
                "message_missing", "account_missing", "account_not_gmail",
                "source_folder_missing", "destination_folder_missing",
                "destination_account_missing", "source_view_missing", "source_view_mismatch",
                "copy_not_supported", "source_account_mismatch", "target_account_mismatch",
                "not_writable", "not_inserted", "callback_exception"
        };
        for (String outcome : outcomes) {
            String line = GmailMutationLog.formatCallback(
                    EntityOperation.MOVE, "label", outcome, null);
            assertTrue("Missing low-cardinality outcome " + outcome,
                    line.contains(" result=" + outcome));
        }

        String rejected = GmailMutationLog.formatCallback(
                EntityOperation.MOVE, "label_928371", "message 9911882@example.test", null);
        assertTrue(rejected.endsWith("target_type=other result=other"));
        assertFalse(rejected.contains("928371"));
        assertFalse(rejected.contains("9911882"));
        assertFalse(rejected.contains("example.test"));
    }

    @Test
    public void callbackValidationAndQueueStatusesMapToDistinctReasons() {
        assertEquals("accepted", GmailMutationLog.validationReason(
                GmailMailMutator.MoveCallbackValidation.READY));
        assertEquals("source_missing", GmailMutationLog.validationReason(
                GmailMailMutator.MoveCallbackValidation.SOURCE_MISSING));
        assertEquals("invalid_target", GmailMutationLog.validationReason(
                GmailMailMutator.MoveCallbackValidation.INVALID_TARGET));

        assertEquals("queued", GmailMutationLog.queueReason(GmailMutationLog.QueueResult.QUEUED));
        assertEquals("not_writable",
                GmailMutationLog.queueReason(GmailMutationLog.QueueResult.NOT_WRITABLE));
        assertEquals("not_inserted",
                GmailMutationLog.queueReason(GmailMutationLog.QueueResult.NOT_INSERTED));
    }

    @Test
    public void moveTargetLoggingUsesOnlyCoarseTrashInboxLabelAndArchiveTypes() {
        assertEquals("trash", GmailMutationLog.targetType(EntityFolder.TRASH));
        assertEquals("inbox", GmailMutationLog.targetType(EntityFolder.INBOX));
        assertEquals("label", GmailMutationLog.targetType(EntityFolder.USER));
        assertEquals("archive", GmailMutationLog.targetType(EntityFolder.ARCHIVE));
        assertEquals("other", GmailMutationLog.targetType("user:Important-Private"));
        assertEquals("trash", GmailMutationLog.deleteTargetType(EntityOperation.DELETE));
        assertEquals("other",
                GmailMutationLog.deleteTargetType(EntityOperation.GMAIL_PERMANENT_DELETE));

        String trash = GmailMutationLog.formatCallback(
                EntityOperation.MOVE, GmailMutationLog.targetType(EntityFolder.TRASH), "queued", null);
        String inboxLabelMove = GmailMutationLog.formatCallback(
                EntityOperation.MOVE, GmailMutationLog.targetType(EntityFolder.USER), "queued", null);
        assertTrue(trash.contains("operation=move target_type=trash"));
        assertTrue(inboxLabelMove.contains("operation=move target_type=label"));
    }

    @Test
    public void generatedAttemptTokenCanBeRecoveredButAccountAndMessageIdsAreRejected() {
        String token = GmailMutationLog.newAttemptToken();
        assertNotNull(GmailMutationLog.safeAttemptToken(token));
        assertTrue(token.matches("gm-[0-9a-f]{32}"));
        assertNull(GmailMutationLog.safeAttemptToken("42"));
        assertNull(GmailMutationLog.safeAttemptToken("1234567890123456789"));
        assertNull(GmailMutationLog.safeAttemptToken("gmail-message-id"));
        assertNull(GmailMutationLog.safeAttemptToken("alice@example.test"));

        JSONArray args = new JSONArray();
        while (args.length() < GmailMutationLog.ATTEMPT_TOKEN_INDEX)
            args.put((Object) null);
        args.put(token);
        assertEquals(token, GmailMutationLog.attemptToken(args));
        assertNull(GmailMutationLog.attemptToken(new JSONArray().put("123456789")));
    }

    @Test
    public void traceArgumentPreservesMoveParametersAndUsesOnlyReservedSlotSix() {
        String token = GmailMutationLog.newAttemptToken();
        Object[] original = {91L, null, null, true, null, "INBOX"};
        Object[] traced = EntityOperation.addGmailAttemptToken(original, token);
        assertEquals(7, traced.length);
        for (int i = 0; i < original.length; i++)
            assertEquals(original[i], traced[i]);
        assertEquals(token, traced[GmailMutationLog.ATTEMPT_TOKEN_INDEX]);
        assertEquals(token, GmailMutationLog.attemptToken(
                EntityOperation.createOperationArgs(traced)));

        Object[] deleteTrace = EntityOperation.addGmailAttemptToken(new Object[0], token);
        assertEquals(7, deleteTrace.length);
        for (int i = 0; i < GmailMutationLog.ATTEMPT_TOKEN_INDEX; i++)
            assertNull(deleteTrace[i]);
        assertEquals(token, deleteTrace[GmailMutationLog.ATTEMPT_TOKEN_INDEX]);
    }

    @Test
    public void formattersDropUntrustedIdentifiersAndNeverPrintCallerSuppliedText() {
        String event = GmailMutationLog.formatEvent(
                "http://mail.example/123", "account-481", "subject: confidential", "481");
        assertEquals("Gmail mutation stage=unknown provider=gmail operation=unknown result=other", event);

        String callback = GmailMutationLog.formatCallback(
                "message-774", "Private label 991", "alice@example.test", "account-481");
        assertEquals("Gmail mutation stage=picker_callback provider=gmail operation=unknown " +
                "target_type=other result=other", callback);
        assertFalse(callback.contains("774"));
        assertFalse(callback.contains("991"));
        assertFalse(callback.contains("alice@example.test"));
        assertFalse(callback.contains("account-481"));

        String correlated = GmailMutationLog.formatEvent("worker_start", EntityOperation.MOVE,
                "started", "gm-0123456789abcdef0123456789abcdef");
        assertTrue(correlated.endsWith("attempt=gm-0123456789abcdef0123456789abcdef"));

        String http = GmailMutationLog.formatHttp(
                "https://mail.example/messages/1188", 403,
                "body: private response", "98765");
        assertEquals("Gmail mutation stage=http provider=gmail operation=unknown status=403 " +
                "result=other error_class=api", http);
        assertFalse(http.contains("mail.example"));
        assertFalse(http.contains("1188"));
        assertFalse(http.contains("private response"));
    }

    @Test
    public void routesSafeEventToProtocolOnlyWhenEnabled() {
        final List<String> protocol = new ArrayList<>();
        ApiMutationLog.ProtocolSink sink = new ApiMutationLog.ProtocolSink() {
            @Override
            public void write(String message) {
                protocol.add(message);
            }
        };
        String token = "gm-0123456789abcdef0123456789abcdef";
        String event = GmailMutationLog.formatCallback(
                EntityOperation.MOVE, "label", "queued", token);

        GmailMutationLog.route(event, true, sink);
        assertEquals(Arrays.asList(event), protocol);
        assertTrue(event.contains("attempt=" + token));
        assertTrue(event.contains("target_type=label"));
        assertFalse(event.contains("account"));
        assertFalse(event.contains("message"));

        protocol.clear();
        GmailMutationLog.route(event, false, sink);
        assertTrue(protocol.isEmpty());
    }

    @Test
    public void protocolMutationEventIsRecordedAndIncludedInNormalLogExport() {
        String event = GmailMutationLog.formatEvent("local_commit", EntityOperation.MOVE,
                "success", "gm-0123456789abcdef0123456789abcdef");
        EntityLog entry = EntityLog.createProtocolEntry(event);

        assertEquals(EntityLog.Type.Protocol, entry.type);
        assertNull(entry.thread);
        assertNull(entry.account);
        assertNull(entry.folder);
        assertNull(entry.message);
        assertEquals(event, entry.data);

        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String exported = DebugHelper.formatLogEntry(entry, format);
        assertEquals(String.format(Locale.US, "%s [%d:0:0:0:0] %s\r\n",
                format.format(entry.time), EntityLog.Type.Protocol.ordinal(), event), exported);
        assertTrue(exported.contains("attempt=gm-0123456789abcdef0123456789abcdef"));
        assertFalse(exported.contains("account="));
        assertFalse(exported.contains("message="));
    }

    @Test
    public void completionAndRefreshSummariesUseFixedLowCardinalityFields() {
        assertEquals("Gmail mutation stage=operation_summary provider=gmail operation=move result=success",
                GmailMutationLog.formatEvent("operation_summary", EntityOperation.MOVE, "success", null));
        assertEquals("Gmail mutation stage=refresh_summary provider=gmail operation=refresh result=scheduled",
                GmailMutationLog.formatEvent("refresh_summary", "refresh", "scheduled", null));
    }
}
