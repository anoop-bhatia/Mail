package eu.faircode.email;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailApiMessageParserTest {
    private final GmailApi.Base64UrlDecoder decoder = value -> Base64.getUrlDecoder().decode(value);

    @Test
    public void separatelyAddressableAttachmentBytesStayOutOfFullBodyResponse() throws Exception {
        JSONObject message = message(
                part("text/plain", "", "body-part", "body", null, 4),
                part("application/pdf", "invoice.pdf", "file-part", "secret", "attachment-1", 6));

        GmailApiMessageParser.Result parseResult = GmailApiMessageParser.parseDetailed(
                Session.getInstance(new Properties()), message, true, decoder, null);
        MimeMessage parsed = parseResult.mimeMessage;
        Multipart multipart = (Multipart) parsed.getContent();
        Part attachment = multipart.getBodyPart(1);

        assertEquals("body", new String(readAll(multipart.getBodyPart(0).getInputStream()),
                StandardCharsets.UTF_8));
        assertEquals("attachment-1", NativeApiContent.getInternalHeader(
                attachment, NativeApiContent.HEADER_REMOTE_ID));
        assertEquals(GmailApiMessageParser.ATTACHMENT_ID, NativeApiContent.getInternalHeader(
                attachment, NativeApiContent.HEADER_REMOTE_TYPE));
        assertEquals(1, parseResult.attachmentParts);
        assertEquals(1, parseResult.attachmentIdParts);
        EntityAttachment row = new EntityAttachment();
        NativeApiContent.applyRemoteMetadata(attachment, row);
        assertEquals("attachment-1", row.provider_id);
        assertEquals(GmailApiMessageParser.ATTACHMENT_ID, row.provider_type);
        try (InputStream bytes = attachment.getInputStream()) {
            assertEquals("The `data` value must be ignored when an attachmentId exists",
                    -1, bytes.read());
        }
    }

    @Test
    public void cidImageWithAttachmentIdKeepsCidMetadataButNotInlineBytes() throws Exception {
        JSONObject image = part("image/png", "logo.png", "image-part",
                "base64-data-must-not-be-used", "inline-image-1", 27);
        JSONArray headers = image.getJSONArray("headers");
        headers.getJSONObject(0).put("value", "inline; filename=\"logo.png\"");
        JSONObject contentId = new JSONObject();
        contentId.put("name", "Content-ID");
        contentId.put("value", "<logo@example>");
        headers.put(contentId);

        MimeMessage parsed = GmailApiMessageParser.parse(
                Session.getInstance(new Properties()), message(image), true, decoder);
        Part attachment = ((Multipart) parsed.getContent()).getBodyPart(0);

        assertEquals("<logo@example>", attachment.getHeader("Content-ID")[0]);
        assertEquals(Part.INLINE, attachment.getDisposition());
        assertEquals("inline-image-1", NativeApiContent.getInternalHeader(
                attachment, NativeApiContent.HEADER_REMOTE_ID));
        assertEquals(GmailApiMessageParser.ATTACHMENT_ID,
                NativeApiContent.getInternalHeader(attachment,
                        NativeApiContent.HEADER_REMOTE_TYPE));
        assertEquals(-1, attachment.getInputStream().read());
    }

    @Test
    public void noAttachmentIdPartBodyDataArrivesInlineWithFullBodyRead() throws Exception {
        JSONObject message = message(
                part("application/octet-stream", "inline.bin", "inline-part", "img", null, 3));

        MimeMessage deferred = GmailApiMessageParser.parse(
                Session.getInstance(new Properties()), message, false, decoder);
        GmailApiMessageParser.Result fullBodyResult = GmailApiMessageParser.parseDetailed(
                Session.getInstance(new Properties()), message, true, decoder, null);
        MimeMessage fullBodyRead = fullBodyResult.mimeMessage;
        Part noIdDeferred = ((Multipart) deferred.getContent()).getBodyPart(0);
        Part noIdFullBody = ((Multipart) fullBodyRead.getContent()).getBodyPart(0);

        assertEquals(NativeApiContent.GMAIL_INLINE_PART, NativeApiContent.getInternalHeader(
                noIdFullBody, NativeApiContent.HEADER_REMOTE_TYPE));
        assertEquals("inline-part", NativeApiContent.getInternalHeader(
                noIdFullBody, NativeApiContent.HEADER_GMAIL_PART_ID));
        assertEquals(1, fullBodyResult.attachmentParts);
        EntityAttachment row = new EntityAttachment();
        NativeApiContent.applyRemoteMetadata(noIdFullBody, row);
        assertEquals("inline-part", row.provider_id);
        assertEquals(NativeApiContent.GMAIL_INLINE_PART, row.provider_type);
        assertTrue("Gmail body.data for a part without attachmentId is part of the full-body response",
                noIdFullBody.getInputStream().read() >= 0);
        assertEquals(-1, noIdDeferred.getInputStream().read());
        assertEquals("img", new String(readAll(noIdFullBody.getInputStream()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void separatelyStoredTextBodyIsFetchedAndNotMisclassifiedAsAnAttachment() throws Exception {
        JSONObject text = part("text/plain", null, "body-part", "ignored", "body-attachment", 4);
        final int[] fetches = {0};

        GmailApiMessageParser.Result result = GmailApiMessageParser.parseDetailed(
                Session.getInstance(new Properties()), message(text), true, decoder, attachmentId -> {
                    fetches[0]++;
                    assertEquals("body-attachment", attachmentId);
                    return "body".getBytes(StandardCharsets.UTF_8);
                });
        Part body = ((Multipart) result.mimeMessage.getContent()).getBodyPart(0);

        assertTrue(result.bodyComplete);
        assertEquals(1, fetches[0]);
        assertEquals(1, result.bodyParts);
        assertEquals(1, result.fetchedBodyParts);
        assertEquals(0, result.incompleteBodyParts);
        assertEquals(0, result.attachmentParts);
        assertEquals("body", new String(readAll(body.getInputStream()), StandardCharsets.UTF_8));
        assertEquals(null, NativeApiContent.getInternalHeader(body, NativeApiContent.HEADER_REMOTE_ID));
    }

    @Test
    public void missingNonemptyTextDataIsIncompleteButAnExplicitEmptyBodyIsComplete() throws Exception {
        JSONObject missing = part("text/html", null, "body-part", "", null, 4);
        missing.getJSONObject("body").remove("data");
        GmailApiMessageParser.Result incomplete = GmailApiMessageParser.parseDetailed(
                Session.getInstance(new Properties()), message(missing), true, decoder, null);

        JSONObject empty = part("text/plain", null, "empty-body", "", null, 0);
        empty.getJSONObject("body").remove("data");
        GmailApiMessageParser.Result legitimateEmpty = GmailApiMessageParser.parseDetailed(
                Session.getInstance(new Properties()), message(empty), true, decoder, null);

        JSONObject unknownSize = part("text/plain", null, "unknown-size", "body", null, 4);
        unknownSize.getJSONObject("body").remove("size");
        GmailApiMessageParser.Result sizeMissing = GmailApiMessageParser.parseDetailed(
                Session.getInstance(new Properties()), message(unknownSize), true, decoder, null);

        assertEquals(false, incomplete.bodyComplete);
        assertEquals(1, incomplete.incompleteBodyParts);
        assertTrue(legitimateEmpty.bodyComplete);
        assertEquals(0, legitimateEmpty.incompleteBodyParts);
        assertEquals(false, sizeMissing.bodyComplete);
        assertEquals(1, sizeMissing.incompleteBodyParts);
    }

    @Test
    public void nonTextAndExplicitTextAttachmentsRemainLazyAndAreCountedAsAttachmentParts() throws Exception {
        JSONObject binary = part("application/pkcs7-mime", "signed.p7m", "part-1",
                "ignored", "binary-attachment", 7);
        JSONObject textAttachment = part("text/plain", "notes.txt", "part-2",
                "ignored", "text-attachment", 7);
        final int[] fetches = {0};

        GmailApiMessageParser.Result result = GmailApiMessageParser.parseDetailed(
                Session.getInstance(new Properties()), message(binary, textAttachment), true,
                decoder, attachmentId -> {
                    fetches[0]++;
                    throw new AssertionError("Ordinary attachment bytes must stay lazy");
                });

        assertTrue("No readable text body is a complete non-body message", result.bodyComplete);
        assertEquals(0, result.bodyParts);
        assertEquals(2, result.attachmentParts);
        assertEquals(2, result.attachmentIdParts);
        assertEquals(0, fetches[0]);
    }

    @Test
    public void nestedGmailPayloadImportsMetadataOnlyFilesButNotTextBodyParts() throws Exception {
        JSONObject plain = part("text/plain", null, "0.0", "plain body", null, 10);
        JSONObject relatedHtml = part("text/html", null, "1.0", "<p>html</p>", null, 11);
        JSONObject inline = part("image/png", null, "1.1", "", "inline-file-1", 5);
        inline.getJSONObject("body").remove("data");
        JSONArray inlineHeaders = inline.getJSONArray("headers");
        inlineHeaders.put(new JSONObject().put("name", "Content-Disposition").put("value", "inline"));
        inlineHeaders.put(new JSONObject().put("name", "Content-ID").put("value", "<logo@example.test>"));
        JSONObject related = multipart("multipart/related", relatedHtml, inline);
        JSONObject alternative = multipart("multipart/alternative", plain, related);

        JSONObject[] files = new JSONObject[8];
        for (int i = 0; i < files.length; i++) {
            String type = (i == 0 ? "text/calendar" :
                    (i == 1 ? "application/pdf" : "application/octet-stream"));
            JSONObject file = part(type,
                    null, "2." + i, "", "file-attachment-" + i, 100 + i);
            file.getJSONObject("body").remove("data");
            files[i] = file;
        }

        JSONObject payload = multipart("multipart/mixed", alternative,
                files[0], files[1], files[2], files[3], files[4], files[5], files[6], files[7]);
        GmailApiMessageParser.Result parsed = GmailApiMessageParser.parseDetailed(
                Session.getInstance(new Properties()), new JSONObject().put("payload", payload),
                true, decoder, null);
        assertEquals(2, parsed.bodyParts);
        assertEquals(9, parsed.attachmentParts);
        assertEquals(9, parsed.attachmentIdParts);
        assertTrue("The synthetic Gmail root must advertise its multipart type before MIME import",
                parsed.mimeMessage.isMimeType("multipart/mixed"));
        assertTrue(parsed.bodyComplete);

        List<GmailApiAttachmentCandidates.Candidate> candidates =
                GmailApiAttachmentCandidates.collect(parsed.mimeMessage);
        assertEquals("The production MIME importer must extract all metadata-only file candidates",
                9, candidates.size());

        int fileRows = 0;
        int inlineRows = 0;
        for (GmailApiAttachmentCandidates.Candidate candidate : candidates) {
            EntityAttachment row = GmailApiAttachmentCandidates.toEntityAttachment(candidate);
            assertTrue("Remote size metadata must survive the empty MIME placeholder",
                    row.size != null && row.size > 0);
            if ("inline-file-1".equals(row.provider_id)) {
                inlineRows++;
                assertEquals("<logo@example.test>", row.cid);
                assertEquals(Part.INLINE, row.disposition);
                assertTrue("CID-inline images must remain eligible for body rendering", row.isInline());
            } else {
                fileRows++;
                assertEquals(GmailApiMessageParser.ATTACHMENT_ID, row.provider_type);
                assertEquals(Part.ATTACHMENT, row.disposition);
                assertTrue("Ordinary Gmail attachment rows must be display-eligible", row.isAttachment());
                try (InputStream bytes = candidate.part.getInputStream()) {
                    assertEquals("Ordinary Gmail attachment bytes must remain lazy", -1, bytes.read());
                }
            }
        }
        assertEquals(8, fileRows);
        assertEquals(1, inlineRows);
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            for (int length = source.read(buffer); length != -1; length = source.read(buffer))
                output.write(buffer, 0, length);
            return output.toByteArray();
        }
    }

    private static JSONObject message(JSONObject... parts) throws Exception {
        JSONObject payload = multipart("multipart/mixed", parts);
        JSONObject message = new JSONObject();
        message.put("payload", payload);
        return message;
    }

    private static JSONObject multipart(String mimeType, JSONObject... parts) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("mimeType", mimeType);
        JSONArray children = new JSONArray();
        for (JSONObject part : parts)
            children.put(part);
        payload.put("parts", children);
        return payload;
    }

    private static JSONObject part(String type, String filename, String id,
                                   String value, String attachmentId, int size) throws Exception {
        JSONObject part = new JSONObject();
        part.put("partId", id);
        part.put("mimeType", type);
        if (filename != null && !filename.isEmpty())
            part.put("filename", filename);
        JSONArray headers = new JSONArray();
        if (filename != null && !filename.isEmpty()) {
            JSONObject disposition = new JSONObject();
            disposition.put("name", "Content-Disposition");
            disposition.put("value", "attachment; filename=\"" + filename + "\"");
            headers.put(disposition);
        }
        part.put("headers", headers);
        JSONObject body = new JSONObject();
        body.put("size", size);
        body.put("data", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        if (attachmentId != null)
            body.put("attachmentId", attachmentId);
        part.put("body", body);
        return part;
    }
}
