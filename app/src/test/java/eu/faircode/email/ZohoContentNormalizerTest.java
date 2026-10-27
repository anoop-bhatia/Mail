package eu.faircode.email;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZohoContentNormalizerTest {
    private static final String BOUNDARY_OUTER = "sanitized-outer";
    private static final String BOUNDARY_RELATED = "sanitized-related";
    private static final String BOUNDARY_ALT = "sanitized-alternative";
    private static final String HTML_BODY = "<html><body><p>HTML_BODY_SENTINEL</p>" +
            "<img src=\"cid:logo@example.test\"></body></html>";

    @Test
    public void nestedMixedAlternativeNormalizesToHtmlAndKeepsZohoLazyAttachmentMetadata()
            throws Exception {
        String wire = "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/mixed; boundary=\"" + BOUNDARY_OUTER + "\"\r\n\r\n" +
                "--" + BOUNDARY_OUTER + "\r\n" +
                "Content-Type: multipart/related; boundary=\"" + BOUNDARY_RELATED +
                        "\"; start=\"<body-root@example.test>\"\r\n\r\n" +
                "--" + BOUNDARY_RELATED + "\r\n" +
                "Content-Type: image/png; name=inline.png\r\n" +
                "Content-Disposition: inline; filename=inline.png\r\n" +
                "Content-ID: <logo@example.test>\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\niVBORw0KGgo=\r\n" +
                "--" + BOUNDARY_RELATED + "\r\n" +
                "Content-Type: multipart/alternative; boundary=\"" + BOUNDARY_ALT + "\"\r\n" +
                "Content-ID: <body-root@example.test>\r\n\r\n" +
                "--" + BOUNDARY_ALT + "\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n\r\n" +
                "PLAIN_BODY_SENTINEL\r\n" +
                "--" + BOUNDARY_ALT + "\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n\r\n" + HTML_BODY + "\r\n" +
                "--" + BOUNDARY_ALT + "--\r\n" +
                "--" + BOUNDARY_RELATED + "--\r\n" +
                "--" + BOUNDARY_OUTER + "\r\n" +
                "Content-Type: application/octet-stream; name=wire-file.bin\r\n" +
                "Content-Disposition: attachment; filename=wire-file.bin\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\nAQIDBA==\r\n" +
                "--" + BOUNDARY_OUTER + "--\r\n";

        ZohoContentNormalizer.Body normalized = ZohoContentNormalizer.normalize(wire);
        assertTrue(normalized.contentType.startsWith("text/html"));
        assertEquals(HTML_BODY, normalized.content);
        assertFalse(normalized.content.contains(BOUNDARY_OUTER));
        assertFalse(normalized.content.contains(BOUNDARY_RELATED));
        assertFalse(normalized.content.contains(BOUNDARY_ALT));
        assertFalse(normalized.content.contains("Content-Type:"));
        assertTrue("HTML CID references must remain available for Zoho inline handling",
                normalized.content.contains("cid:logo@example.test"));

        NativeApiContent.Attachment ordinary = new NativeApiContent.Attachment(
                "ordinary-id", "folder-fixture", ZohoApi.ORDINARY_ATTACHMENT_TYPE,
                "ordinary.bin", "application/octet-stream", Part.ATTACHMENT,
                null, 4L, null, false);
        NativeApiContent.Attachment inline = new NativeApiContent.Attachment(
                "inline-id", "folder-fixture", ZohoApi.INLINE_ATTACHMENT_TYPE,
                "inline.png", "image/png", Part.INLINE, "logo@example.test",
                3L, null, true);
        MimeMessage message = NativeApiContent.createMessage(
                Session.getInstance(new Properties()),
                "Content-Type: text/plain; charset=UTF-8\r\n" +
                        "Content-Transfer-Encoding: quoted-printable\r\n",
                null, normalized.contentType, normalized.content,
                java.util.Arrays.asList(ordinary, inline));
        MimeMessage reparsed = roundTrip(message);
        assertEquals("multipart/mixed", new javax.mail.internet.ContentType(
                reparsed.getContentType()).getBaseType());

        Multipart mixed = (Multipart) reparsed.getContent();
        assertEquals(2, mixed.getCount());
        Multipart related = (Multipart) mixed.getBodyPart(0).getContent();
        Part html = related.getBodyPart(0);
        assertEquals("text/html", new javax.mail.internet.ContentType(
                html.getContentType()).getBaseType());
        assertEquals(HTML_BODY, html.getContent());
        assertFalse(html.getContent().toString().contains(BOUNDARY_OUTER));

        EntityAttachment inlineStored = new EntityAttachment();
        NativeApiContent.applyRemoteMetadata(related.getBodyPart(1), inlineStored);
        assertEquals("inline-id", inlineStored.provider_id);
        assertEquals(ZohoApi.INLINE_ATTACHMENT_TYPE, inlineStored.provider_type);
        assertEquals("<logo@example.test>", related.getBodyPart(1).getHeader("Content-ID")[0]);
        assertEquals(Long.valueOf(3L), inlineStored.size);
        assertFalse(inlineStored.available);
        assertNoBytes(related.getBodyPart(1));

        EntityAttachment ordinaryStored = new EntityAttachment();
        NativeApiContent.applyRemoteMetadata(mixed.getBodyPart(1), ordinaryStored);
        assertEquals("ordinary-id", ordinaryStored.provider_id);
        assertEquals(ZohoApi.ORDINARY_ATTACHMENT_TYPE, ordinaryStored.provider_type);
        assertEquals(Long.valueOf(4L), ordinaryStored.size);
        assertFalse(ordinaryStored.available);
        assertNoBytes(mixed.getBodyPart(1));
    }

    @Test
    public void relatedAlternativeWithTwoInlineImagesKeepsOnlySafeStructureCounts()
            throws Exception {
        String boundary = "synthetic-related-boundary";
        String alternative = "synthetic-alternative-boundary";
        String html = "<html><body><img src=\"cid:first@fixture.invalid\">" +
                "<img src=\"cid:second@fixture.invalid\"></body></html>";
        String wire = "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/related; boundary=\"" + boundary + "\"\r\n\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Type: multipart/alternative; boundary=\"" + alternative + "\"\r\n\r\n" +
                "--" + alternative + "\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n\r\nfixture plain body\r\n" +
                "--" + alternative + "\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n\r\n" + html + "\r\n" +
                "--" + alternative + "--\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Type: image/png\r\n" +
                "Content-Disposition: inline\r\n" +
                "Content-ID: <first@fixture.invalid>\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\nAQID\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Disposition: inline\r\n" +
                "Content-ID: <second@fixture.invalid>\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\nBAUG\r\n" +
                "--" + boundary + "--\r\n";

        ZohoContentNormalizer.Body normalized = ZohoContentNormalizer.normalize(wire);

        assertEquals("text/html", new javax.mail.internet.ContentType(
                normalized.contentType).getBaseType());
        assertEquals(html, normalized.content);
        assertEquals("related>alternative>html", normalized.mimePath);
        assertEquals(1, normalized.textPlainParts);
        assertEquals(1, normalized.textHtmlParts);
        assertEquals(2, normalized.inlineResources);
        assertEquals(2, normalized.inlineImages);
        assertEquals(2, normalized.cidReferences);
    }

    @Test
    public void escapedMimeInsideZohoPlainHtmlSelectsBodyAndMatchesLazyInlineMetadata()
            throws Exception {
        String outer = "fixture-wrapped-outer";
        String related = "fixture-wrapped-related";
        String alternative = "fixture-wrapped-alternative";
        String cid = "asset@fixture.invalid";
        String aliasCid = "second-asset@fixture.invalid";
        String html = "<html><body><p>WRAPPED_BODY_SENTINEL</p>" +
                "<img src=\"cid:" + cid + "\"><img src=\"cid:" + aliasCid + "\"></body></html>";
        String serialized = "--" + outer + "\r\n" +
                "Content-Type: multipart/related; boundary=\"" + related + "\"\r\n\r\n" +
                "--" + related + "\r\n" +
                "Content-Type: multipart/alternative; boundary=\"" + alternative + "\"\r\n\r\n" +
                "--" + alternative + "\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n\r\nWRAPPED_PLAIN_SENTINEL\r\n" +
                "--" + alternative + "\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n\r\n" +
                escapeHtmlEntities(html) + "\r\n" +
                "--" + alternative + "--\r\n" +
                "--" + related + "\r\n" +
                "Content-Type: image/png\r\n" +
                "Content-Disposition: inline; filename=fixture-image.png\r\n" +
                "Content-ID: <" + cid + ">\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\nAQID\r\n" +
                "--" + related + "\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Disposition: inline; filename=fixture-second-image.jpg\r\n" +
                "Content-ID: <" + aliasCid + ">\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\nBAUG\r\n" +
                "--" + related + "--\r\n" +
                "--" + outer + "--\r\n";
        String response = "<!doctype html><html><body><div x-plain=\"true\">\r\n" +
                serialized + "</div></body></html>";

        assertEquals("nested", ZohoContentNormalizer.sourceKind(response));
        ZohoContentNormalizer.Body normalized = ZohoContentNormalizer.normalize(response);
        assertEquals("text/html", new javax.mail.internet.ContentType(
                normalized.contentType).getBaseType());
        assertEquals(html, normalized.content);
        assertEquals("mixed>related>alternative>html", normalized.mimePath);
        assertEquals(1, normalized.textPlainParts);
        assertEquals(1, normalized.textHtmlParts);
        assertEquals(2, normalized.inlineResources);
        assertEquals(2, normalized.inlineImages);
        assertEquals(2, normalized.cidReferences);
        assertFalse(normalized.content.contains(outer));
        assertFalse(normalized.content.contains("Content-Type:"));

        JSONArray inlineRows = new JSONArray().put(new JSONObject()
                .put("attachmentId", "remote-inline-fixture")
                .put("attachmentName", "fixture-image.png")
                .put("cid", cid)
                .put("contentType", "image/png")
                .put("attachmentSize", 3)).put(new JSONObject()
                .put("attachmentId", "remote-inline-alias-fixture")
                .put("attachmentName", "fixture-second-image.jpg")
                .put("contentId", aliasCid)
                .put("contentType", "image/jpeg")
                .put("attachmentSize", 4));
        JSONArray ordinaryRows = new JSONArray().put(new JSONObject()
                .put("attachmentId", "remote-file-fixture")
                .put("attachmentName", "fixture-file.bin")
                .put("contentType", "application/octet-stream")
                .put("attachmentSize", 4));
        List<NativeApiContent.Attachment> remoteAttachments = new ArrayList<>();
        ZohoApi.addAttachmentInfo(remoteAttachments, ordinaryRows, "folder-fixture", false);
        ZohoApi.addAttachmentInfo(remoteAttachments, inlineRows, "folder-fixture", true);
        assertEquals(3, remoteAttachments.size());
        assertEquals(cid, remoteAttachments.get(1).contentId);
        assertEquals(aliasCid, remoteAttachments.get(2).contentId);
        MimeMessage message = NativeApiContent.createMessage(
                Session.getInstance(new Properties()), null, null,
                normalized.contentType, normalized.content,
                remoteAttachments);
        Multipart mixed = (Multipart) roundTrip(message).getContent();
        assertEquals(2, mixed.getCount());
        Multipart relatedBody = (Multipart) mixed.getBodyPart(0).getContent();
        assertEquals(html, relatedBody.getBodyPart(0).getContent());
        Part inlinePart = relatedBody.getBodyPart(1);
        assertEquals("<" + cid + ">", inlinePart.getHeader("Content-ID")[0]);
        EntityAttachment inlineStored = new EntityAttachment();
        NativeApiContent.applyRemoteMetadata(inlinePart, inlineStored);
        assertEquals("remote-inline-fixture", inlineStored.provider_id);
        assertEquals(ZohoApi.INLINE_ATTACHMENT_TYPE, inlineStored.provider_type);
        assertEquals(Long.valueOf(3L), inlineStored.size);
        assertFalse(inlineStored.available);
        assertNoBytes(inlinePart);

        Part aliasInlinePart = relatedBody.getBodyPart(2);
        assertEquals("<" + aliasCid + ">", aliasInlinePart.getHeader("Content-ID")[0]);
        EntityAttachment aliasInlineStored = new EntityAttachment();
        NativeApiContent.applyRemoteMetadata(aliasInlinePart, aliasInlineStored);
        assertEquals("remote-inline-alias-fixture", aliasInlineStored.provider_id);
        assertEquals(ZohoApi.INLINE_ATTACHMENT_TYPE, aliasInlineStored.provider_type);
        assertFalse(aliasInlineStored.available);
        assertNoBytes(aliasInlinePart);

        EntityAttachment ordinaryStored = new EntityAttachment();
        NativeApiContent.applyRemoteMetadata(mixed.getBodyPart(1), ordinaryStored);
        assertEquals("remote-file-fixture", ordinaryStored.provider_id);
        assertEquals(ZohoApi.ORDINARY_ATTACHMENT_TYPE, ordinaryStored.provider_type);
        assertFalse(ordinaryStored.available);
        assertNoBytes(mixed.getBodyPart(1));
    }

    @Test
    public void ordinaryZohoHtmlBodyRemainsUnchanged() throws Exception {
        String html = "<div>BODY_SENTINEL</div>";
        ZohoContentNormalizer.Body body = ZohoContentNormalizer.normalize(html);
        assertEquals("text/html; charset=UTF-8", body.contentType);
        assertEquals(html, body.content);

        String plainWrapper = "<html><body><div x-plain=\"true\">ordinary fixture text" +
                "</div></body></html>";
        assertEquals("text", ZohoContentNormalizer.sourceKind(plainWrapper));
        assertEquals(plainWrapper, ZohoContentNormalizer.normalize(plainWrapper).content);
    }

    @Test
    public void rootlessZohoMultipartAlternativeNormalizesToItsHtmlPart() throws Exception {
        String boundary = Long.toHexString(System.nanoTime());
        String html = "<html><body><p>synthetic html fixture</p></body></html>";
        String content = "--" + boundary + "\r\n\r\nsynthetic plain fixture\r\n" +
                "--" + boundary + "\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n\r\n" + html + "\r\n" +
                "--" + boundary + "--\r\n";

        assertEquals("multipart", ZohoContentNormalizer.sourceKind(content));
        assertTrue(ZohoContentNormalizer.hasMultipartBoundary(content));

        ZohoContentNormalizer.Body normalized = ZohoContentNormalizer.normalize(content);
        assertEquals("text/html", new javax.mail.internet.ContentType(
                normalized.contentType).getBaseType());
        assertEquals(html, normalized.content);
        assertEquals("mixed>html", normalized.mimePath);
        assertEquals(1, normalized.textPlainParts);
        assertEquals(1, normalized.textHtmlParts);
        assertFalse(normalized.content.contains(boundary));
        assertFalse(normalized.content.contains("Content-Type:"));
    }

    @Test
    public void zohoInlineHtmlBuildsPlainAlternativeWithoutChangingHtmlOrRemoteImages()
            throws Exception {
        String html = "<html><body><p>VISIBLE_BODY_SENTINEL</p>" +
                "<img src=\"/mail/ImageDisplay?cid=first%40fixture.invalid\">" +
                "<img src=\"/mail/ImageDisplay?cid=second%40fixture.invalid\"></body></html>";
        List<NativeApiContent.Attachment> attachments = new ArrayList<>();
        attachments.add(new NativeApiContent.Attachment(
                "remote-inline-first", "folder-fixture", ZohoApi.INLINE_ATTACHMENT_TYPE,
                "first.png", "image/png", Part.INLINE, "first@fixture.invalid",
                3L, null, true));
        attachments.add(new NativeApiContent.Attachment(
                "remote-inline-second", "folder-fixture", ZohoApi.INLINE_ATTACHMENT_TYPE,
                "second.jpg", "image/jpeg", Part.INLINE, "second@fixture.invalid",
                4L, null, true));
        attachments.add(new NativeApiContent.Attachment(
                "remote-file", "folder-fixture", ZohoApi.ORDINARY_ATTACHMENT_TYPE,
                "document.pdf", "application/pdf", Part.ATTACHMENT, null,
                5L, null, false));

        MimeMessage message = NativeApiContent.createZohoMessage(
                Session.getInstance(new Properties()), null,
                "text/html; charset=UTF-8", html, attachments);
        MimeMessage reparsed = roundTrip(message);
        Multipart mixed = (Multipart) reparsed.getContent();
        assertEquals("multipart/mixed", new javax.mail.internet.ContentType(
                reparsed.getContentType()).getBaseType());
        assertEquals(2, mixed.getCount());

        Multipart related = (Multipart) mixed.getBodyPart(0).getContent();
        assertEquals("multipart/related", new javax.mail.internet.ContentType(
                mixed.getBodyPart(0).getContentType()).getBaseType());
        assertEquals(3, related.getCount());

        Multipart alternative = (Multipart) related.getBodyPart(0).getContent();
        assertEquals("multipart/alternative", new javax.mail.internet.ContentType(
                related.getBodyPart(0).getContentType()).getBaseType());
        assertEquals(2, alternative.getCount());
        assertEquals("text/plain", new javax.mail.internet.ContentType(
                alternative.getBodyPart(0).getContentType()).getBaseType());
        assertEquals("VISIBLE_BODY_SENTINEL", alternative.getBodyPart(0).getContent());
        assertEquals("text/html", new javax.mail.internet.ContentType(
                alternative.getBodyPart(1).getContentType()).getBaseType());
        assertEquals(html, alternative.getBodyPart(1).getContent());

        EntityAttachment firstInline = new EntityAttachment();
        NativeApiContent.applyRemoteMetadata(related.getBodyPart(1), firstInline);
        assertEquals("remote-inline-first", firstInline.provider_id);
        assertEquals("<first@fixture.invalid>", related.getBodyPart(1).getHeader("Content-ID")[0]);
        assertNoBytes(related.getBodyPart(1));

        EntityAttachment secondInline = new EntityAttachment();
        NativeApiContent.applyRemoteMetadata(related.getBodyPart(2), secondInline);
        assertEquals("remote-inline-second", secondInline.provider_id);
        assertEquals("<second@fixture.invalid>", related.getBodyPart(2).getHeader("Content-ID")[0]);
        assertNoBytes(related.getBodyPart(2));

        EntityAttachment ordinary = new EntityAttachment();
        NativeApiContent.applyRemoteMetadata(mixed.getBodyPart(1), ordinary);
        assertEquals("remote-file", ordinary.provider_id);
        assertFalse(ordinary.available);
        assertNoBytes(mixed.getBodyPart(1));
    }

    @Test
    public void zohoSyntheticMimeUsesMessageHelperHtmlLeafWithoutMultipartFallback()
            throws Exception {
        String html = "<html><body><p>VISIBLE_BODY_SENTINEL</p>" +
                "<img src=\"/mail/ImageDisplay?cid=first%40fixture.invalid\">" +
                "<img src=\"/mail/ImageDisplay?cid=second%40fixture.invalid\"></body></html>";
        ZohoContentNormalizer.Body normalized = ZohoContentNormalizer.normalize(html);
        assertEquals(html, normalized.content);
        assertTrue(normalized.contentType.startsWith("text/html"));

        List<NativeApiContent.Attachment> inline = new ArrayList<>();
        inline.add(new NativeApiContent.Attachment("remote-inline-first", "fixture-folder",
                ZohoApi.INLINE_ATTACHMENT_TYPE, "first.png", "image/png", Part.INLINE,
                "first@fixture.invalid", 3L, null, true));
        inline.add(new NativeApiContent.Attachment("remote-inline-second", "fixture-folder",
                ZohoApi.INLINE_ATTACHMENT_TYPE, "second.jpg", "image/jpeg", Part.INLINE,
                "second@fixture.invalid", 4L, null, true));

        MimeMessage synthetic = NativeApiContent.createZohoMessage(
                Session.getInstance(new Properties()), null, normalized.contentType,
                normalized.content, inline);
        javax.mail.Multipart mixed = (javax.mail.Multipart) synthetic.getContent();
        assertTrue(mixed.getContentType().startsWith("multipart/mixed"));
        javax.mail.BodyPart relatedPart = mixed.getBodyPart(0);
        assertTrue("related part type=" + relatedPart.getContentType() +
                        " mixed_count=" + mixed.getCount() +
                        " inline_count=" + inline.size() +
                        " first_related=" + inline.get(0).related +
                        " first_disposition=" + inline.get(0).disposition,
                relatedPart.isMimeType("multipart/related"));
        javax.mail.Multipart relatedTree = (javax.mail.Multipart) relatedPart.getContent();
        javax.mail.BodyPart alternativePart = relatedTree.getBodyPart(0);
        assertTrue(alternativePart.isMimeType("multipart/alternative"));
        javax.mail.Multipart alternativeTree = (javax.mail.Multipart) alternativePart.getContent();
        assertTrue(alternativeTree.getBodyPart(0).isMimeType("text/plain"));
        assertTrue(alternativeTree.getBodyPart(1).isMimeType("text/html"));

        MessageHelper helper = new MessageHelper(synthetic, null, false,
                "b29a69da-24f2-4ff7-9f12-1c8bced87b60");
        MessageHelper.MessageParts parsed = helper.getMessageParts();
        parsed.normalize();

        String rendered = parsed.getHtml(null, false, null,
                "b29a69da-24f2-4ff7-9f12-1c8bced87b60");
        assertEquals("MessageHelper should choose the HTML alternative, not wrap/fallback to multipart text",
                html, rendered);
        assertEquals("Both plain and HTML body leaves should be recorded by MessageHelper",
                Integer.valueOf(0x80), parsed.isPlainOnly());
        assertFalse(rendered.contains("x-plain=\"true\""));
        assertEquals(0, parsed.getDiagnosticPlainMultipartFallbackCount());
    }

    @Test
    public void stagePredicatesCountOnlySafeZohoMimeAndInlineStructure() {
        String html = "<img src=\"/mail/ImageDisplay?imgWidth=80&amp;cid=private%40fixture.invalid\">";
        String wrappedMime = "<div x-plain=\"true\">Content-Type: text/html\r\n\r\n" +
                "--private-boundary\r\n--private-boundary--</div>";

        assertFalse(ZohoContentNormalizer.hasMultipartBoundary(html));
        assertEquals(1, ZohoContentNormalizer.countImageDisplayCidReferences(html));
        assertEquals(0, ZohoContentNormalizer.countCidReferences(html));
        assertTrue(ZohoContentNormalizer.hasXPlainWrapper(wrappedMime));
        assertTrue(ZohoContentNormalizer.hasMultipartBoundary(wrappedMime));
    }

    @Test
    public void emptyHeaderPrefixDoesNotCauseSubstringBoundsFailure() throws Exception {
        StringBuilder builder = new StringBuilder("\r\n\r\n  ");
        while (builder.length() < 12148)
            builder.append('x');
        String content = builder.toString();

        assertEquals(12148, content.length());
        assertEquals("text", ZohoContentNormalizer.sourceKind(content));
        assertEquals(content, ZohoContentNormalizer.normalize(content).content);
    }

    @Test
    public void zohoMultipartWithoutReadableBodyIsRejectedInsteadOfReturnedAsWireText() throws Exception {
        String wire = "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/mixed; boundary=\"attachment-only\"\r\n\r\n" +
                "--attachment-only\r\n" +
                "Content-Type: application/octet-stream; name=fixture.bin\r\n" +
                "Content-Disposition: attachment; filename=fixture.bin\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\nAQIDBA==\r\n" +
                "--attachment-only--\r\n";
        try {
            ZohoContentNormalizer.normalize(wire);
            fail("MIME without a readable body must not be returned as displayable wire text");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().contains("attachment-only"));
            assertFalse(expected.getMessage().contains("fixture.bin"));
        }
    }

    private static void assertNoBytes(Part part) throws Exception {
        assertTrue("A metadata-only placeholder must not report materialized bytes",
                part.getSize() <= 0);
        try (InputStream input = part.getInputStream()) {
            assertEquals(-1, input.read());
        }
    }

    private static String escapeHtmlEntities(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static MimeMessage roundTrip(MimeMessage message) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        message.writeTo(output);
        return new MimeMessage(Session.getInstance(new Properties()),
                new java.io.ByteArrayInputStream(output.toByteArray()));
    }
}
