package eu.faircode.email;
/*
    This file is part of FairEmail.
    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.
    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.ContentType;
import javax.mail.internet.MimeMessage;

/** Normalizes Zoho's readable body response without changing shared MIME parsing. */
final class ZohoContentNormalizer {
    private static final String HTML = "text/html; charset=UTF-8";
    private static final Pattern MULTIPART_HEADER = Pattern.compile(
            "(?im)^content-type\\s*:\\s*multipart/[a-z0-9!#$&^_.+-]+\\b");
    private static final Pattern TEXT_HTML_HEADER = Pattern.compile(
            "(?im)^content-type[ \\t]*:[ \\t]*text/html\\b");
    private static final Pattern MIME_DELIMITER = Pattern.compile(
            "(?m)^--([A-Za-z0-9'()+_,./:=?-]{1,70})[ \\t]*\\r?$");
    private static final Pattern CID_REFERENCE = Pattern.compile("(?i)\\bcid:");
    private static final Pattern IMAGE_DISPLAY_ROUTE = Pattern.compile(
            "(?i)/mail/ImageDisplay\\?([^\\s\\\"'<>]*)");
    private static final Pattern X_PLAIN_TRUE = Pattern.compile(
            "(?i)\\bx-plain\\s*=\\s*(['\"])true\\1");
    private static final int MAX_NESTING = 32;

    private ZohoContentNormalizer() {
    }

    static final class Body {
        final String contentType;
        final String content;
        final String mimePath;
        final int textPlainParts;
        final int textHtmlParts;
        final int inlineResources;
        final int inlineImages;
        final int cidReferences;

        Body(String contentType, String content) {
            this(contentType, content, "other", 0, 0, 0, 0, 0);
        }

        Body(String contentType, String content, String mimePath,
             int textPlainParts, int textHtmlParts, int inlineResources,
             int inlineImages, int cidReferences) {
            this.contentType = contentType;
            this.content = content;
            this.mimePath = mimePath;
            this.textPlainParts = textPlainParts;
            this.textHtmlParts = textHtmlParts;
            this.inlineResources = inlineResources;
            this.inlineImages = inlineImages;
            this.cidReferences = cidReferences;
        }
    }

    static Body normalize(String content) throws IOException {
        if (content == null)
            return new Body(HTML, "", "html", 0, 1, 0, 0, 0);

        String mime = (isMultipartEnvelope(content) ? content : findNestedMime(content));
        if (mime == null)
            mime = findBareMultipart(content);
        if (mime == null)
            return new Body(HTML, content, "html", 0, 1, 0, 0,
                    countCidReferences(content));

        return normalizeMultipart(mime);
    }

    private static Body normalizeMultipart(String content) throws IOException {
        try {
            MimeMessage source = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            Body body = findBody(source, 0);
            if (body == null || body.content == null)
                throw new IOException("Zoho message content MIME did not contain a readable text body");
            Structure structure = new Structure();
            collectStructure(source, 0, structure);
            return new Body(body.contentType, body.content, body.mimePath,
                    structure.textPlainParts, structure.textHtmlParts,
                    structure.inlineResources, structure.inlineImages,
                    countCidReferences(body.content));
        } catch (IOException ex) {
            throw ex;
        } catch (MessagingException | RuntimeException ex) {
            // Do not expose parser details that may contain message headers or body fragments.
            throw new IOException("Zoho message content MIME could not be normalized");
        }
    }

    static String sourceKind(String content) {
        if (content == null || content.isEmpty())
            return "empty";
        if (isMultipartEnvelope(content))
            return "multipart";
        if (findNestedMime(content) != null)
            return "nested";
        return (findBareMultipart(content) == null ? "text" : "multipart");
    }

    static boolean hasMultipartBoundary(String content) {
        return (content != null &&
                (isMultipartEnvelope(content) || findNestedMime(content) != null ||
                        findBareMultipart(content) != null));
    }

    static int countImageDisplayCidReferences(String content) {
        if (content == null || content.isEmpty())
            return 0;
        int count = 0;
        Matcher routes = IMAGE_DISPLAY_ROUTE.matcher(content);
        while (routes.find()) {
            String query = routes.group(1).replace("&amp;", "&").replace("&#38;", "&");
            for (String parameter : query.split("&", -1)) {
                int equals = parameter.indexOf('=');
                String name = (equals < 0 ? parameter : parameter.substring(0, equals));
                try {
                    name = URLDecoder.decode(name, StandardCharsets.UTF_8.name());
                } catch (Exception ignored) {
                    // An undecodable key is not a recognized Zoho CID query.
                }
                if ("cid".equalsIgnoreCase(name)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    static boolean hasXPlainWrapper(String content) {
        return (content != null && X_PLAIN_TRUE.matcher(content).find());
    }

    private static boolean isMultipartEnvelope(String content) {
        int headerEnd = findHeaderEnd(content);
        if (headerEnd < 0)
            return false;

        int first = 0;
        while (first < content.length() && Character.isWhitespace(content.charAt(first)))
            first++;
        if (first >= headerEnd)
            return false;

        int lineEnd = content.indexOf('\n', first);
        if (lineEnd < 0 || lineEnd > headerEnd)
            lineEnd = headerEnd;
        String firstHeader = content.substring(first, lineEnd).trim();
        if (!firstHeader.matches("[A-Za-z0-9][A-Za-z0-9-]*[ \\t]*:.*"))
            return false;

        return MULTIPART_HEADER.matcher(content.substring(first, headerEnd)).find();
    }

    private static String findNestedMime(String content) {
        if (content == null || content.toLowerCase(Locale.ROOT).indexOf("x-plain") < 0)
            return null;

        Element plain = null;
        for (Element div : Jsoup.parse(content).select("div"))
            if ("true".equalsIgnoreCase(div.attr("x-plain").trim())) {
                plain = div;
                break;
            }
        if (plain == null)
            return null;

        // wholeText decodes only the outer HTML entity layer while preserving MIME line breaks.
        String payload = plain.wholeText();
        if (!TEXT_HTML_HEADER.matcher(payload).find())
            return null;
        if (isMultipartEnvelope(payload))
            return payload;

        // Zoho can expose a serialized MIME entity as text without its root multipart header.
        // Infer a safe synthetic mixed root only from a valid opening delimiter and matching close.
        Matcher delimiter = MIME_DELIMITER.matcher(payload);
        if (!delimiter.find())
            return null;
        String boundary = delimiter.group(1);
        if (boundary.endsWith("--"))
            return null;
        Pattern closing = Pattern.compile("(?m)^--" + Pattern.quote(boundary) +
                "--[ \\t]*\\r?$");
        if (!closing.matcher(payload).find())
            return null;

        return "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"" +
                boundary + "\"\r\n\r\n" + payload;
    }

    private static String findBareMultipart(String content) {
        if (content == null || content.isEmpty() ||
                !TEXT_HTML_HEADER.matcher(content).find())
            return null;

        int first = 0;
        while (first < content.length() && Character.isWhitespace(content.charAt(first)))
            first++;

        Matcher delimiter = MIME_DELIMITER.matcher(content);
        if (!delimiter.find() || delimiter.start() != first)
            return null;
        String boundary = delimiter.group(1);
        if (boundary.endsWith("--"))
            return null;

        Pattern opening = Pattern.compile("(?m)^--" + Pattern.quote(boundary) +
                "[ \\t]*\\r?$");
        Matcher openings = opening.matcher(content);
        if (!openings.find() || openings.start() != first || !openings.find())
            return null;

        Matcher htmlHeader = TEXT_HTML_HEADER.matcher(content);
        if (!htmlHeader.find(openings.start()))
            return null;

        Pattern closing = Pattern.compile("(?m)^--" + Pattern.quote(boundary) +
                "--[ \\t]*\\r?$");
        Matcher end = closing.matcher(content);
        if (!end.find(htmlHeader.start()))
            return null;

        return "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"" +
                boundary + "\"\r\n\r\n" + content;
    }

    private static int findHeaderEnd(String content) {
        int crlf = content.indexOf("\r\n\r\n");
        int lf = content.indexOf("\n\n");
        if (crlf < 0)
            return lf;
        if (lf < 0)
            return crlf;
        return Math.min(crlf, lf);
    }

    private static Body findBody(Part part, int depth) throws IOException, MessagingException {
        if (depth > MAX_NESTING)
            throw new IOException("Zoho message content MIME nesting is too deep");
        if (isAttachment(part))
            return null;

        if (part.isMimeType("multipart/*")) {
            Object content = part.getContent();
            if (!(content instanceof Multipart))
                throw new IOException("Zoho message content MIME multipart could not be parsed");

            Multipart multipart = (Multipart) content;
            ContentType contentType = new ContentType(part.getContentType());
            String subtype = contentType.getSubType().toLowerCase(Locale.ROOT);
            if ("related".equals(subtype)) {
                int root = getRelatedRoot(multipart, contentType.getParameter("start"));
                Body body = (root < 0 ? null : findBody(multipart.getBodyPart(root), depth + 1));
                return (body == null ? null : prependPath("related", body));
            }

            List<Body> candidates = new ArrayList<>();
            for (int i = 0; i < multipart.getCount(); i++) {
                Body candidate = findBody(multipart.getBodyPart(i), depth + 1);
                if (candidate != null)
                    candidates.add(candidate);
            }
            if (candidates.isEmpty())
                return null;

            Body body = ("alternative".equals(subtype)
                    ? best(candidates) : combineBest(candidates));
            return (body == null ? null : prependPath(safeMultipartType(subtype), body));
        }

        boolean html = part.isMimeType("text/html");
        boolean plain = part.isMimeType("text/plain");
        if (!html && !plain)
            return null;

        ContentType contentType = new ContentType(part.getContentType());
        String charsetName = contentType.getParameter("charset");
        Charset charset = (charsetName == null || charsetName.trim().isEmpty()
                ? StandardCharsets.UTF_8 : Charset.forName(charsetName));
        String text;
        try (java.io.InputStream input = part.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int length = input.read(buffer); length != -1; length = input.read(buffer))
                output.write(buffer, 0, length);
            text = new String(output.toByteArray(), charset);
        }
        return new Body(contentType.toString(), text,
                (html ? "html" : "plain"), 0, 0, 0, 0, 0);
    }

    private static final class Structure {
        int textPlainParts;
        int textHtmlParts;
        int inlineResources;
        int inlineImages;
    }

    private static void collectStructure(Part part, int depth, Structure structure)
            throws IOException, MessagingException {
        if (depth > MAX_NESTING)
            throw new IOException("Zoho message content MIME nesting is too deep");

        boolean multipart = part.isMimeType("multipart/*");
        if (!multipart) {
            boolean plain = part.isMimeType("text/plain");
            boolean html = part.isMimeType("text/html");
            if (!isAttachment(part)) {
                if (plain)
                    structure.textPlainParts++;
                if (html)
                    structure.textHtmlParts++;
            }
            if (!plain && !html && isInlineResource(part)) {
                structure.inlineResources++;
                if (part.isMimeType("image/*"))
                    structure.inlineImages++;
            }
            return;
        }

        if (isAttachment(part))
            return;
        Object content = part.getContent();
        if (!(content instanceof Multipart))
            throw new IOException("Zoho message content MIME multipart could not be parsed");
        Multipart multipartContent = (Multipart) content;
        for (int i = 0; i < multipartContent.getCount(); i++)
            collectStructure(multipartContent.getBodyPart(i), depth + 1, structure);
    }

    private static boolean isInlineResource(Part part) throws MessagingException {
        if (Part.INLINE.equalsIgnoreCase(part.getDisposition()))
            return true;
        String[] contentId = part.getHeader("Content-ID");
        return (contentId != null && contentId.length > 0);
    }

    static int countCidReferences(String content) {
        if (content == null || content.isEmpty())
            return 0;
        int count = 0;
        Matcher matcher = CID_REFERENCE.matcher(content);
        while (matcher.find())
            count++;
        return count;
    }

    private static Body prependPath(String type, Body body) {
        return new Body(body.contentType, body.content,
                type + ">" + body.mimePath, 0, 0, 0, 0, 0);
    }

    private static String safeMultipartType(String subtype) {
        return ("related".equals(subtype) || "alternative".equals(subtype) ||
                "mixed".equals(subtype) || "report".equals(subtype) ||
                "signed".equals(subtype) || "encrypted".equals(subtype) ||
                "digest".equals(subtype) || "parallel".equals(subtype)
                ? subtype : "other");
    }

    private static boolean isAttachment(Part part) throws MessagingException {
        return (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) ||
                (!(part instanceof MimeMessage) && part.getFileName() != null));
    }

    private static int getRelatedRoot(Multipart multipart, String start)
            throws MessagingException {
        if (multipart.getCount() == 0)
            return -1;
        if (start != null && !start.trim().isEmpty()) {
            String wanted = normalizeContentId(start);
            for (int i = 0; i < multipart.getCount(); i++) {
                String[] contentId = multipart.getBodyPart(i).getHeader("Content-ID");
                if (contentId != null && contentId.length > 0 &&
                        wanted.equals(normalizeContentId(contentId[0])))
                    return i;
            }
            return -1;
        }
        return 0;
    }

    private static String normalizeContentId(String value) {
        String result = value.trim();
        if (result.startsWith("<") && result.endsWith(">"))
            result = result.substring(1, result.length() - 1);
        return result;
    }

    private static Body best(List<Body> candidates) {
        Body selected = null;
        for (Body candidate : candidates)
            if (selected == null || score(candidate) > score(selected))
                selected = candidate;
        return selected;
    }

    private static Body combineBest(List<Body> candidates) {
        Body selected = best(candidates);
        if (selected == null)
            return null;
        StringBuilder content = new StringBuilder();
        for (Body candidate : candidates)
            if (score(candidate) == score(selected)) {
                if (content.length() > 0)
                    content.append('\n');
                content.append(candidate.content);
            }
        return new Body(selected.contentType, content.toString(), selected.mimePath,
                0, 0, 0, 0, 0);
    }

    private static int score(Body body) {
        return (body.contentType.toLowerCase(Locale.ROOT).startsWith("text/html") ? 2 : 1);
    }
}
