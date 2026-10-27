package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HtmlImagePlaceholderTest {
    @Test
    public void blockedNativeApiImageSourcesUseTheSharedPlaceholderPolicy() {
        String[] sources = {
                "cid:gmail-inline@example.test",
                "cid:graph-inline@example.test",
                "/mail/ImageDisplay?cid=zoho-inline%40example.test",
                "https://cdn.example.test/image.png"
        };
        for (String source : sources) {
            assertTrue(source, HtmlImagePlaceholder.isBlocked(source, false, false));
            assertFalse(source, HtmlImagePlaceholder.isBlocked(source, true, false));
        }

        assertFalse("Allowed CID images retain the inline-image preference",
                HtmlImagePlaceholder.isBlocked("cid:logo@example.test", false, true));
    }

    @Test
    public void blockedLinkedImageKeepsItsVisibleImageLinkLabel() {
        assertEquals("[Image link]", HtmlImagePlaceholder.getLabel("", true, "Image link"));
        assertEquals("[Chart]", HtmlImagePlaceholder.getLabel("Chart", true, "Image link"));
        assertNull(HtmlImagePlaceholder.getLabel("", false, "Image link"));
    }
}
