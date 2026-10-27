package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AttachmentSubtitleFormatterTest {
    @Test
    public void zohoInlineSubtitleHidesCidAndRelatedSectionMetadata() {
        EntityAttachment attachment = createImageAttachment();
        attachment.provider_type = ZohoApi.INLINE_ATTACHMENT_TYPE;

        assertEquals("image/png", AttachmentSubtitleFormatter.format(attachment, true));
        assertEquals("image/png", AttachmentSubtitleFormatter.format(attachment, false));
    }

    @Test
    public void otherAttachmentRowsKeepExistingDebugSubtitle() {
        EntityAttachment attachment = createImageAttachment();
        String expected = "image/png <23abc@pc27> true null/1";

        assertEquals(expected, AttachmentSubtitleFormatter.format(attachment, true));

        attachment.provider_type = ZohoApi.ORDINARY_ATTACHMENT_TYPE;
        assertEquals(expected, AttachmentSubtitleFormatter.format(attachment, true));
    }

    private static EntityAttachment createImageAttachment() {
        EntityAttachment attachment = new EntityAttachment();
        attachment.type = "image/png";
        attachment.cid = "<23abc@pc27>";
        attachment.related = true;
        attachment.section = null;
        attachment.sequence = 1;
        return attachment;
    }
}
