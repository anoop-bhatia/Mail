package eu.faircode.email;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class NativeApiReplyTest {
    @Test
    public void replyUsesOnlyTheProviderNativeSourceIdentifier() {
        EntityMessage source = new EntityMessage();
        source.thread = "fairEmail-internal-thread-not-provider-id";
        source.gmail_id = "gmail-message-id";
        source.gmail_thread_id = "gmail-provider-thread";
        source.zoho_id = "12345";
        source.graph_id = "graph-message-id";

        assertEquals("gmail-provider-thread",
                NativeApiReply.gmailThreadId(Collections.singletonList(source)));
        assertEquals("12345", NativeApiReply.zohoMessageId(Collections.singletonList(source)));
        assertEquals("graph-message-id", NativeApiReply.graphMessageId(Collections.singletonList(source)));
    }

    @Test
    public void ambiguousOrMissingNativeReplySourceNeverFallsBackToInternalThread() {
        EntityMessage first = new EntityMessage();
        first.gmail_id = "gmail-1";
        first.gmail_thread_id = "thread-1";
        EntityMessage second = new EntityMessage();
        second.gmail_id = "gmail-2";
        second.gmail_thread_id = "thread-2";
        second.thread = "thread-2";

        assertNull(NativeApiReply.gmailThreadId(Arrays.asList(first, second)));
        assertNull(NativeApiReply.gmailThreadId(Collections.<EntityMessage>emptyList()));
        assertNull(NativeApiReply.zohoMessageId(Collections.singletonList(new EntityMessage())));
        assertNull(NativeApiReply.graphMessageId(Collections.singletonList(new EntityMessage())));
    }

    @Test
    public void sentReconciliationRequiresExactIdentityAccountFolderAndNarrowTime() {
        EntityFolder sent = new EntityFolder("Sent", EntityFolder.SENT);
        sent.id = 9L;
        sent.account = 4L;
        EntityMessage local = sentCopy(4L, 9L, "<outgoing@example.invalid>", 1_000_000L);

        assertSame(local, NativeSentMessageMatcher.findUnique(Collections.singletonList(local),
                4L, sent, "<outgoing@example.invalid>", 1_000_010L));
        assertNull(NativeSentMessageMatcher.findUnique(Collections.singletonList(local),
                5L, sent, "<outgoing@example.invalid>", 1_000_010L));
        assertNull(NativeSentMessageMatcher.findUnique(Collections.singletonList(local),
                4L, sent, "<different@example.invalid>", 1_000_010L));
        assertNull(NativeSentMessageMatcher.findUnique(Collections.singletonList(local),
                4L, sent, "<outgoing@example.invalid>",
                1_000_000L + NativeSentMessageMatcher.MAX_SENT_TIME_DIFFERENCE + 1));
    }

    @Test
    public void sentReconciliationRejectsAlreadyLinkedOrAmbiguousRows() {
        EntityFolder sent = new EntityFolder("Sent", EntityFolder.SENT);
        sent.id = 9L;
        sent.account = 4L;
        EntityMessage local = sentCopy(4L, 9L, "<outgoing@example.invalid>", 1_000_000L);
        EntityMessage duplicate = sentCopy(4L, 9L, "<outgoing@example.invalid>", 1_000_000L);

        assertNull(NativeSentMessageMatcher.findUnique(Arrays.asList(local, duplicate),
                4L, sent, "<outgoing@example.invalid>", 1_000_000L));
    }

    @Test
    public void graphDirect202AndLargeUploadSentSyncReuseOneVisibleLocalRow() {
        assertTrue(MicrosoftGraph.isSendAcceptedStatus(202));
        for (String sendPath : Arrays.asList("direct-202", "large-upload")) {
            EntityFolder sent = new EntityFolder("Sent", EntityFolder.SENT);
            sent.id = 9L;
            sent.account = 4L;
            EntityMessage local = sentCopy(4L, 9L, "<" + sendPath + "@example.invalid>", 2_000_000L);
            local.ui_hide = false;
            GraphMailApi.MailMessage remote = new GraphMailApi.MailMessage(
                    "graph-id-" + sendPath, 2_000_000L, 2_000_100L, null,
                    true, false, false, "conversation-1", local.msgid);
            List<EntityMessage> rows = new ArrayList<>();
            rows.add(local);

            EntityMessage matched = GraphMailReceiver.findLocalSentCopy(
                    rows, 4L, sent, remote);

            assertSame(sendPath, local, matched);
            local.graph_id = remote.id;
            local.thread = GraphMailReceiver.getThreadKey(4L, remote.id, remote.conversationId);
            assertEquals(1, rows.size());
            assertSame(local, rows.get(0));
            assertEquals(remote.id, local.graph_id);
            assertEquals("graph:4:conversation-1", local.thread);
        }
    }

    private static EntityMessage sentCopy(long account, long folder, String msgid, long sent) {
        EntityMessage message = new EntityMessage();
        message.account = account;
        message.folder = folder;
        message.msgid = msgid;
        message.sent = sent;
        return message;
    }
}
