package eu.faircode.email;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GraphDraftFlowTest {
    @Test
    public void sendsOnlyAfterEveryLargeAttachmentUploadCompletes() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        GraphAttachmentPlan.Plan plan = plan(2);

        GraphDraftFlow.send(transport, message(), plan);

        assertEquals(Arrays.asList("draft", "upload:one.bin", "upload:two.bin", "send"),
                transport.events);
        assertEquals(0, transport.deleteCount);
    }

    @Test
    public void largeAttachmentReplyCreatesDraftFromProviderSourceMessage() throws Exception {
        RecordingTransport transport = new RecordingTransport();

        GraphDraftFlow.send(transport, "graph-source-id", message(), plan(1));

        assertEquals(Arrays.asList("reply-draft:graph-source-id", "upload:one.bin", "send"),
                transport.events);
        assertEquals(1, transport.sendCount);
        assertEquals(0, transport.deleteCount);
    }

    @Test
    public void uploadFailurePreventsSendAndBestEffortDeletesTheDraft() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        transport.failUpload = true;
        try {
            GraphDraftFlow.send(transport, message(), plan(2));
            fail("Expected upload failure");
        } catch (IOException expected) {
            assertEquals(Arrays.asList("draft", "upload:one.bin", "delete"), transport.events);
            assertEquals(0, transport.sendCount);
            assertEquals(1, transport.deleteCount);
        }
    }

    @Test
    public void expiredFlowDoesNotStartPostDeadlineDraftCleanup() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        transport.failUpload = true;
        transport.cancelled = true;
        try {
            GraphDraftFlow.send(transport, message(), plan(1));
            fail("Expected upload failure");
        } catch (IOException expected) {
            assertEquals(Arrays.asList("draft", "upload:one.bin"), transport.events);
            assertEquals(0, transport.sendCount);
            assertEquals(0, transport.deleteCount);
        }
    }

    @Test
    public void ambiguousSendIsCalledOnceAndDraftIsNotDeleted() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        transport.failSendAmbiguously = true;
        try {
            GraphDraftFlow.send(transport, message(), plan(1));
            fail("Expected an ambiguous send response");
        } catch (GraphSendAttempt.SendTimeoutException expected) {
            assertTrue(expected.outcomeUnknown);
            assertEquals(Arrays.asList("draft", "upload:one.bin", "send"), transport.events);
            assertEquals(1, transport.sendCount);
            assertEquals(0, transport.deleteCount);
        }
    }

    private static MimeMessage message() throws MessagingException {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    private static GraphAttachmentPlan.Plan plan(int count) {
        List<GraphAttachmentPlan.Attachment> attachments = new ArrayList<>();
        for (int index = 0; index < count; index++)
            attachments.add(new GraphAttachmentPlan.Attachment(new File("unused"),
                    GraphAttachmentPlan.LARGE_ATTACHMENT_THRESHOLD,
                    (index == 0 ? "one.bin" : "two.bin"), "application/octet-stream", null, false));
        return new GraphAttachmentPlan.Plan(attachments,
                count * GraphAttachmentPlan.LARGE_ATTACHMENT_THRESHOLD, false);
    }

    private static final class RecordingTransport implements GraphDraftFlow.Transport {
        final List<String> events = new ArrayList<>();
        boolean failUpload;
        boolean failSendAmbiguously;
        boolean cancelled;
        int sendCount;
        int deleteCount;

        @Override
        public String createDraft(MimeMessage message) {
            events.add("draft");
            return "draft-id";
        }

        @Override
        public String createReplyDraft(String sourceMessageId, MimeMessage message) {
            events.add("reply-draft:" + sourceMessageId);
            return "reply-draft-id";
        }

        @Override
        public void uploadAttachment(String messageId, GraphAttachmentPlan.Attachment attachment)
                throws IOException {
            events.add("upload:" + attachment.name);
            if (failUpload)
                throw new IOException("upload failed");
        }

        @Override
        public void sendDraft(String messageId) throws IOException {
            events.add("send");
            sendCount++;
            if (failSendAmbiguously)
                throw new GraphSendAttempt.SendTimeoutException(
                        GraphSendAttempt.Phase.WAITING_RESPONSE, -1, true, 10,
                        "send response lost");
        }

        @Override
        public void deleteDraft(String messageId) {
            events.add("delete");
            deleteCount++;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public GraphAttachmentUpload.Session createSession(String messageId,
                GraphAttachmentPlan.Attachment attachment) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GraphAttachmentUpload.Session getUploadStatus(String opaqueUploadUrl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GraphAttachmentUpload.PutResult putRange(String opaqueUploadUrl, File file,
                GraphAttachmentUpload.Range range, long totalSize) {
            throw new UnsupportedOperationException();
        }
    }
}
