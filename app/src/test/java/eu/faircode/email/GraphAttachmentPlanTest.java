package eu.faircode.email;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GraphAttachmentPlanTest {
    @Test
    public void followsMicrosoftThreeMegabyteRoutingBoundary() {
        assertFalse(GraphAttachmentPlan.usesUploadSession(
                GraphAttachmentPlan.LARGE_ATTACHMENT_THRESHOLD - 1));
        assertTrue(GraphAttachmentPlan.usesUploadSession(
                GraphAttachmentPlan.LARGE_ATTACHMENT_THRESHOLD));
        assertEquals(150L * 1024L * 1024L, GraphAttachmentPlan.MAX_ATTACHMENT_SIZE);
    }

    @Test
    public void rejectsFilesAboveMicrosoftLimitWithoutMutatingMime() throws Exception {
        File tooLarge = sizedFile(GraphAttachmentPlan.MAX_ATTACHMENT_SIZE + 1);
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            MimeMultipart mixed = new MimeMultipart("mixed");
            MimeBodyPart body = new MimeBodyPart();
            body.setText("message body", "UTF-8");
            mixed.addBodyPart(body);
            mixed.addBodyPart(filePart(tooLarge, "too-large.bin", Part.ATTACHMENT, null));
            message.setContent(mixed);

            try {
                GraphAttachmentPlan.prepare(message);
                throw new AssertionError("Expected the documented 150 MB limit");
            } catch (IllegalArgumentException expected) {
                assertEquals(2, mixed.getCount());
            }
        } finally {
            tooLarge.delete();
        }
    }

    @Test
    public void removesOnlyLargeFilePartAndRetainsSmallFilesAndMessageBody() throws Exception {
        File large = sizedFile(GraphAttachmentPlan.LARGE_ATTACHMENT_THRESHOLD);
        File small = sizedFile(GraphAttachmentPlan.LARGE_ATTACHMENT_THRESHOLD - 1);
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            MimeMultipart mixed = new MimeMultipart("mixed");
            MimeBodyPart body = new MimeBodyPart();
            body.setText("message body", "UTF-8");
            mixed.addBodyPart(body);
            mixed.addBodyPart(filePart(large, "large.bin", Part.ATTACHMENT, null));
            mixed.addBodyPart(filePart(small, "small.bin", Part.ATTACHMENT, null));
            message.setContent(mixed);

            GraphAttachmentPlan.Plan plan = GraphAttachmentPlan.prepare(message);

            assertTrue(plan.requiresUploadSessions());
            assertFalse(plan.directFallback);
            assertEquals(1, plan.attachments.size());
            assertEquals(large.length(), plan.attachments.get(0).size);
            assertEquals(2, mixed.getCount());
            assertEquals("large.bin", plan.attachments.get(0).name);
            assertEquals("message body", ((MimeBodyPart) mixed.getBodyPart(0)).getContent());
        } finally {
            large.delete();
            small.delete();
        }
    }

    @Test
    public void retainsInlineContentIdAndFallsBackForSignedMime() throws Exception {
        File large = sizedFile(GraphAttachmentPlan.LARGE_ATTACHMENT_THRESHOLD);
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            MimeMultipart signed = new MimeMultipart("signed");
            MimeBodyPart signedContent = new MimeBodyPart();
            signedContent.setText("signed content", "UTF-8");
            signed.addBodyPart(signedContent);
            MimeBodyPart inline = filePart(large, "inline.png", Part.INLINE, "<image@example.test>");
            signed.addBodyPart(inline);
            message.setContent(signed);

            GraphAttachmentPlan.Plan plan = GraphAttachmentPlan.prepare(message);

            assertFalse(plan.requiresUploadSessions());
            assertTrue(plan.directFallback);
            assertEquals(2, signed.getCount());
        } finally {
            large.delete();
        }
    }

    @Test
    public void extractsInlineContentIdWithoutAngleBracketsWhenMimeCanBeSafelyRewritten() throws Exception {
        File large = sizedFile(GraphAttachmentPlan.LARGE_ATTACHMENT_THRESHOLD);
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            MimeMultipart related = new MimeMultipart("related");
            MimeBodyPart body = new MimeBodyPart();
            body.setContent("<img src=\"cid:image@example.test\">", "text/html; charset=UTF-8");
            related.addBodyPart(body);
            related.addBodyPart(filePart(large, "inline.png", Part.INLINE, "<image@example.test>"));
            message.setContent(related);

            GraphAttachmentPlan.Plan plan = GraphAttachmentPlan.prepare(message);

            assertTrue(plan.requiresUploadSessions());
            assertTrue(plan.attachments.get(0).inline);
            assertEquals("image@example.test", plan.attachments.get(0).contentId);
            assertEquals(1, related.getCount());
        } finally {
            large.delete();
        }
    }

    private static MimeBodyPart filePart(File file, String name, String disposition,
                                         String contentId) throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new FileDataSource(file)));
        part.setFileName(name);
        part.setDisposition(disposition);
        part.setHeader("Content-Type", "application/octet-stream");
        if (contentId != null)
            part.setHeader("Content-ID", contentId);
        return part;
    }

    private static File sizedFile(long size) throws Exception {
        File file = File.createTempFile("graph-attachment-test", ".bin");
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(size);
        }
        return file;
    }
}
