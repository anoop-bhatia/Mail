package eu.faircode.email;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import javax.mail.BodyPart;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;
import javax.activation.DataHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Offline round-trip checks for provider-created native BODY MIME structures. */
public class NativeApiBodyMimeRoundTripTest {
    private static final String HTML_BODY =
            "<html><body><p>FIRST_HTML_SEGMENT</p>" +
                    "<pre>Content-Type: text/plain\r\n\r\n--not-a-real-mime-boundary</pre>" +
                    "<p>LAST_HTML_SEGMENT</p></body></html>";
    private static final String TEXT_BODY =
            "FIRST_TEXT_SEGMENT\r\nContent-Type: text/html\r\n\r\n" +
                    "--not-a-real-mime-boundary\r\nLAST_TEXT_SEGMENT";

    @Test
    public void graphHtmlAndTextBodiesRetainHeadersAndFirstAndLastSegments() throws Exception {
        Session session = Session.getInstance(new Properties());
        for (String type : new String[]{"html", "text"}) {
            JSONObject response = new JSONObject()
                    .put("id", "fixture-message")
                    .put("body", new JSONObject()
                            .put("contentType", type)
                            .put("content", "html".equals(type) ? HTML_BODY : TEXT_BODY))
                    .put("internetMessageHeaders", headerArray(true));
            GraphMailApi api = new GraphMailApi(null, "fixture-token",
                    url -> new ZohoTestHttpsConnection(url, 200, response.toString()));
            GraphMailApi.MessageDetails details = api.getMessage("fixture-message", true);
            String contentType = ("text".equalsIgnoreCase(details.bodyType)
                    ? "text/plain; charset=UTF-8" : "text/html; charset=UTF-8");
            MimeMessage mime = NativeApiContent.createMessage(session, null, details.headers,
                    contentType, details.body, new ArrayList<NativeApiContent.Attachment>());
            MimeMessage reparsed = roundTrip(session, mime);
            assertHeaderMarkers(reparsed);
            assertEquals("multipart/mixed", baseType(reparsed.getContentType()));
            assertEquals("html".equals(type) ? "text/html" : "text/plain",
                    baseType(((Multipart) reparsed.getContent()).getBodyPart(0).getContentType()));
            assertEquals(java.util.Collections.singletonList("html".equals(type) ? HTML_BODY : TEXT_BODY),
                    collectTextParts(reparsed));
        }
    }

    @Test
    public void zohoRawHeadersAndDecodedHtmlBodyRetainTheirFirstAndLastSegments() throws Exception {
        Session session = Session.getInstance(new Properties());
        String rawHeaders = "X-First-Sentinel: HEADER_START\r\n" +
                "X-Long-Sentinel: " + foldedLongHeader() + "\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n" +
                "Content-Transfer-Encoding: quoted-printable\r\n" +
                "Content-Disposition: inline\r\n";
        String bodyJson = new JSONObject().put("status", new JSONObject().put("code", 200))
                .put("data", new JSONObject().put("content", HTML_BODY)).toString();
        String headersJson = new JSONObject().put("status", new JSONObject().put("code", 200))
                .put("data", new JSONObject().put("headerContent", rawHeaders)).toString();
        ZohoApi api = new ZohoApi("fixture-token", "https://www.zohoapis.eu", "fixture-account",
                url -> new ZohoTestHttpsConnection(url, 200,
                        (url.getPath().endsWith("/header") ? headersJson : bodyJson)));

        String receivedHeaders = api.getMessageHeaders("fixture-folder", "fixture-message");
        String receivedBody = api.getMessageContent("fixture-folder", "fixture-message");
        MimeMessage mime = NativeApiContent.createMessage(session, receivedHeaders, null,
                "text/html; charset=UTF-8", receivedBody,
                new ArrayList<NativeApiContent.Attachment>());
        assertEquals("multipart/mixed", baseType(mime.getContentType()));
        assertEquals("text/html", baseType(((Multipart) mime.getContent()).getBodyPart(0).getContentType()));
        assertEquals(java.util.Collections.singletonList(HTML_BODY), collectTextParts(mime));
        MimeMessage reparsed = roundTrip(session, mime);

        assertHeaderMarkers(reparsed);
        assertNull(reparsed.getHeader("Content-Transfer-Encoding", null));
        assertNull(reparsed.getHeader("Content-Disposition", null));
        assertEquals("multipart/mixed", baseType(reparsed.getContentType()));
        assertEquals("text/html", baseType(((Multipart) reparsed.getContent()).getBodyPart(0).getContentType()));
        assertEquals(java.util.Collections.singletonList(HTML_BODY), collectTextParts(reparsed));
    }

    @Test
    public void zohoAttachmentMetadataCreatesAnUnavailableZeroByteMimePlaceholder() throws Exception {
        Session session = Session.getInstance(new Properties());
        NativeApiContent.Attachment remote = new NativeApiContent.Attachment(
                "inline-file-id", "folder-1", ZohoApi.INLINE_ATTACHMENT_TYPE,
                "logo.png", "image/png", Part.INLINE, "logo@example.test", 3L,
                null, true);
        MimeMessage mime = NativeApiContent.createMessage(session, null, null,
                "text/html; charset=UTF-8", "<img src=\"cid:logo@example.test\">",
                java.util.Collections.singletonList(remote));
        Multipart mixed = (Multipart) mime.getContent();
        Multipart related = (Multipart) mixed.getBodyPart(0).getContent();
        Part placeholder = related.getBodyPart(1);
        EntityAttachment attachment = new EntityAttachment();
        NativeApiContent.applyRemoteMetadata(placeholder, attachment);

        assertTrue("The synthetic attachment part must not claim a byte count", placeholder.getSize() <= 0);
        try (java.io.InputStream input = placeholder.getInputStream()) {
            assertEquals(-1, input.read());
        }
        assertFalse("A fresh metadata placeholder is not a cached attachment", attachment.available);
        assertEquals("inline-file-id", attachment.provider_id);
        assertEquals(ZohoApi.INLINE_ATTACHMENT_TYPE, attachment.provider_type);
        assertEquals(Long.valueOf(3L), attachment.size);
    }

    @Test
    public void zohoEmptyBodyDoesNotRetainOriginalMimeEntityHeaders() throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage mime = NativeApiContent.createMessage(session,
                "X-First-Sentinel: HEADER_START\r\n" +
                        "Content-Type: multipart/alternative; boundary=old\r\n" +
                        "Content-Transfer-Encoding: base64\r\n",
                null, null, null, new ArrayList<NativeApiContent.Attachment>());
        MimeMessage reparsed = roundTrip(session, mime);

        assertTrue(contains(reparsed.getHeader("X-First-Sentinel", null), "HEADER_START"));
        assertEquals("text/plain", baseType(reparsed.getContentType()));
        assertEquals("7bit", reparsed.getHeader("Content-Transfer-Encoding", null));
        assertEquals("", reparsed.getContent().toString().trim());
    }

    @Test
    public void gmailAlternativeKeepsFirstPlainPartAndFollowingHtmlPart() throws Exception {
        Session session = Session.getInstance(new Properties());
        JSONObject payload = new JSONObject()
                .put("mimeType", "multipart/alternative")
                .put("headers", headerArray(false))
                .put("parts", new JSONArray()
                        .put(gmailLeaf("text/plain", TEXT_BODY))
                        .put(gmailLeaf("text/html", HTML_BODY)));
        MimeMessage mime = GmailApiMessageParser.parse(session,
                new JSONObject().put("payload", payload), true,
                value -> Base64.getUrlDecoder().decode(value));
        MimeMessage reparsed = roundTrip(session, mime);

        assertHeaderMarkers(reparsed);
        assertEquals("multipart/alternative", baseType(reparsed.getContentType()));
        assertEquals(java.util.Arrays.asList(TEXT_BODY, HTML_BODY), collectTextParts(reparsed));
    }

    @Test
    public void warningShapeRequiresATextPlainPartWhoseInMemoryContentIsActuallyMultipart() throws Exception {
        MimeMultipart nested = new MimeMultipart("related");
        MimeBodyPart html = new MimeBodyPart();
        html.setDataHandler(new DataHandler(new ByteArrayDataSource(
                HTML_BODY.getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8")));
        html.setHeader("Content-Type", "text/html; charset=UTF-8");
        nested.addBodyPart(html);

        MimeBodyPart mismatched = new MimeBodyPart();
        mismatched.setContent(nested);
        mismatched.setHeader("Content-Type", "text/plain; charset=UTF-8");

        assertEquals("text/plain", baseType(mismatched.getContentType()));
        Object actual = mismatched.getContent();
        assertTrue(actual instanceof Multipart);
        assertEquals("text/html", baseType(((Multipart) actual).getBodyPart(0).getContentType()));

        // A legitimate HTML-only native body has a multipart container and a text/html leaf;
        // the leaf itself is never marked text/plain or returned as Multipart.
        MimeMessage graph = NativeApiContent.createMessage(Session.getInstance(new Properties()),
                null, null, "text/html; charset=UTF-8", HTML_BODY,
                new ArrayList<NativeApiContent.Attachment>());
        Multipart outer = (Multipart) graph.getContent();
        Part leaf = outer.getBodyPart(0);
        assertEquals("text/html", baseType(leaf.getContentType()));
        assertTrue(!(leaf.getContent() instanceof Multipart));
    }

    private static JSONArray headerArray(boolean includeMimeType) throws Exception {
        JSONArray headers = new JSONArray()
                .put(new JSONObject().put("name", "X-First-Sentinel").put("value", "HEADER_START"))
                .put(new JSONObject().put("name", "X-Long-Sentinel").put("value", foldedLongHeader()));
        if (includeMimeType)
            headers.put(new JSONObject().put("name", "Content-Type")
                    .put("value", "text/plain; charset=UTF-8"));
        return headers;
    }

    private static String foldedLongHeader() {
        StringBuilder value = new StringBuilder("HEADER_LONG_START ");
        for (int i = 0; i < 180; i++)
            value.append("segment-").append(i).append(' ');
        value.append("\r\n\tHEADER_LONG_END");
        return value.toString();
    }

    private static JSONObject gmailLeaf(String mimeType, String body) throws Exception {
        JSONObject headers = new JSONObject().put("name", "Content-Type").put("value", mimeType);
        JSONObject payload = new JSONObject().put("mimeType", mimeType)
                .put("headers", new JSONArray().put(headers))
                .put("body", new JSONObject().put("size", body.length()).put("data",
                        Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(body.getBytes(StandardCharsets.UTF_8))));
        return payload;
    }

    private static MimeMessage roundTrip(Session session, MimeMessage message) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return new MimeMessage(session, new ByteArrayInputStream(output.toByteArray()));
    }

    private static List<String> collectTextParts(Part part) throws Exception {
        List<String> result = new ArrayList<>();
        collectTextParts(part, result);
        return result;
    }

    private static void collectTextParts(Part part, List<String> result) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Object content = part.getContent();
            if (!(content instanceof Multipart))
                throw new AssertionError("Multipart MIME part did not parse as Multipart: " + part.getContentType());
            Multipart multipart = (Multipart) content;
            for (int i = 0; i < multipart.getCount(); i++)
                collectTextParts(multipart.getBodyPart(i), result);
        } else if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
            try (java.io.InputStream input = part.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                for (int length = input.read(buffer); length != -1; length = input.read(buffer))
                    output.write(buffer, 0, length);
                result.add(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        }
    }

    private static void assertHeaderMarkers(MimeMessage message) throws Exception {
        assertTrue(contains(message.getHeader("X-First-Sentinel", null), "HEADER_START"));
        String longHeader = message.getHeader("X-Long-Sentinel", null);
        assertTrue(contains(longHeader, "HEADER_LONG_START"));
        assertTrue(contains(longHeader, "HEADER_LONG_END"));
    }

    private static boolean contains(String value, String marker) {
        return (value != null && value.contains(marker));
    }

    private static String baseType(String contentType) throws Exception {
        return new javax.mail.internet.ContentType(contentType).getBaseType();
    }
}
