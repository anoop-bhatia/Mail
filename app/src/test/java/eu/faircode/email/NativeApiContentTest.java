package eu.faircode.email;

import org.junit.Test;

import java.io.InputStream;
import java.util.Collections;
import java.util.Properties;

import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import static org.junit.Assert.assertEquals;

public class NativeApiContentTest {
    @Test
    public void providerAttachmentIdsSurviveMetadataImportWithoutDownloadingBytes() throws Exception {
        assertProviderAttachment("gmail-attachment", null,
                GmailApiMessageParser.ATTACHMENT_ID, 8L);
        assertProviderAttachment("zoho-attachment", "zoho-folder", "zoho", 8L);
        assertProviderAttachment("graph-attachment", null,
                "#microsoft.graph.fileAttachment", 8L);
    }

    private static void assertProviderAttachment(String id, String folder, String providerType,
                                                 Long size) throws Exception {
        NativeApiContent.Attachment source = new NativeApiContent.Attachment(
                id, folder, providerType, "file.bin", "application/octet-stream",
                Part.ATTACHMENT, null, size, null, false);
        Session session = Session.getInstance(new Properties(), null);
        MimeMessage message = NativeApiContent.createMessage(session, null, null,
                null, null, Collections.singletonList(source));
        Multipart multipart = (Multipart) message.getContent();
        assertEquals(1, multipart.getCount());
        Part part = multipart.getBodyPart(0);

        EntityAttachment stored = new EntityAttachment();
        NativeApiContent.applyRemoteMetadata(part, stored);

        assertEquals(id, stored.provider_id);
        assertEquals(folder, stored.provider_folder);
        assertEquals(providerType, stored.provider_type);
        assertEquals(size, stored.size);
        try (InputStream input = part.getInputStream()) {
            assertEquals("Metadata import must not materialize attachment bytes", -1,
                    input.read());
        }
    }
}
