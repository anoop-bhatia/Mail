package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The same attachment must be accepted or rejected identically on Gmail, Graph and Zoho. Gmail's
 * documented 25 MB per-file ceiling is the strictest, so it is the shared one; a whole MIME
 * message may be larger because it can carry several attachments.
 */
public class AttachmentLimitsTest {
    @Test
    public void everyProviderUsesTheSameAttachmentCeiling() {
        assertEquals(25 * 1024 * 1024, AttachmentLimits.MAX_ATTACHMENT_BYTES);
        assertEquals(AttachmentLimits.MAX_ATTACHMENT_BYTES, GmailApi.MAX_ATTACHMENT_BYTES);
        assertEquals(AttachmentLimits.MAX_ATTACHMENT_BYTES, GraphMailApi.MAX_ATTACHMENT_BYTES);
        assertEquals(AttachmentLimits.MAX_ATTACHMENT_BYTES, ZohoApi.MAX_ATTACHMENT_BYTES);
        assertTrue("A whole MIME message may carry several attachments",
                GraphMailApi.MAX_MIME_BYTES > GraphMailApi.MAX_ATTACHMENT_BYTES);
    }

    @Test
    public void gmailDecodesUpToTheSharedCeilingAndRejectsMore() throws Exception {
        // Eight base64url characters cannot decode into two bytes, so it is rejected up front.
        try {
            GmailApi.decodeAttachmentData("AAAAAAAA", 2, value -> new byte[0]);
            throw new AssertionError("Expected the shared attachment ceiling to reject this data");
        } catch (java.io.IOException ex) {
            assertTrue(ex.getMessage().contains("exceeds the local size limit"));
        }
        // A decoder that returns more than the requested ceiling is rejected as well.
        try {
            GmailApi.decodeAttachmentData("AAAA", 2, value -> new byte[]{1, 2, 3});
            throw new AssertionError("Expected decoded size to be checked");
        } catch (java.io.IOException ex) {
            assertTrue(ex.getMessage().contains("exceeds the local size limit"));
        }
        assertEquals(2, GmailApi.decodeAttachmentData("AAAA", 3, value -> new byte[]{1, 2}).length);
    }

    @Test
    public void noProviderKeepsAPrivateAttachmentNumberOrLooserUploadMessage() throws Exception {
        String zoho = source("ZohoApi");
        assertTrue(zoho.contains("MAX_ATTACHMENT_BYTES = AttachmentLimits.MAX_ATTACHMENT_BYTES"));
        assertTrue(zoho.contains("exceeds the local size limit"));
        String graph = source("GraphMailApi");
        assertTrue(graph.contains("MAX_ATTACHMENT_BYTES = AttachmentLimits.MAX_ATTACHMENT_BYTES"));
        // The attachment download must be bounded by the shared ceiling, not the MIME bound.
        assertTrue(graph.contains(
                "segment(attachmentId) + \"/$value\", \"application/octet-stream\",\n                MAX_ATTACHMENT_BYTES"));
        String gmail = source("GmailApi");
        assertTrue(gmail.contains("MAX_ATTACHMENT_BYTES = AttachmentLimits.MAX_ATTACHMENT_BYTES"));
    }

    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve(name + ".java"));
    }
}
