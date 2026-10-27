package eu.faircode.email;

import android.content.Context;

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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.activation.DataHandler;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import org.json.JSONObject;
import org.jsoup.Jsoup;

/** Builds MIME structures from provider JSON while keeping attachment bytes out of sync responses. */
final class NativeApiContent {
    static final String HEADER_REMOTE_ID = "X-FairEmail-Remote-Attachment-Id";
    static final String HEADER_REMOTE_FOLDER = "X-FairEmail-Remote-Attachment-Folder";
    static final String HEADER_REMOTE_TYPE = "X-FairEmail-Remote-Attachment-Type";
    static final String HEADER_REMOTE_SIZE = "X-FairEmail-Remote-Attachment-Size";
    static final String HEADER_GMAIL_PART_ID = "X-FairEmail-Gmail-Part-Id";
    static final String GMAIL_INLINE_PART = "gmail-inline-part";

    private NativeApiContent() {
    }

    static final class Header {
        final String name;
        final String value;

        Header(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    static final class Attachment {
        final String id;
        final String folder;
        final String providerType;
        final String name;
        final String contentType;
        final String disposition;
        final String contentId;
        final Long size;
        final byte[] inlineBytes;
        final boolean related;

        Attachment(String id, String folder, String providerType, String name,
                   String contentType, String disposition, String contentId,
                   Long size, byte[] inlineBytes, boolean related) {
            this.id = id;
            this.folder = folder;
            this.providerType = providerType;
            this.name = name;
            this.contentType = contentType;
            this.disposition = disposition;
            this.contentId = contentId;
            this.size = size;
            this.inlineBytes = inlineBytes;
            this.related = related;
        }
    }

    static MimeMessage createMessage(Session session, String rawHeaders,
                                     List<Header> headers, String bodyContentType,
                                     String body, List<Attachment> attachments)
            throws IOException, MessagingException {
        return createMessage(session, rawHeaders, headers, bodyContentType, body, attachments, false);
    }

    static MimeMessage createZohoMessage(Session session, String rawHeaders,
                                        String bodyContentType, String body,
                                        List<Attachment> attachments)
            throws IOException, MessagingException {
        return createMessage(session, rawHeaders, null, bodyContentType, body, attachments, true);
    }

    static void logZohoMimeTree(Context context, String traceId, Part root) {
        if (!ZohoApiDiagnostics.isTraceEnabled(context))
            return;
        try {
            logZohoMimePart(context, traceId, root, "root", -1, 1, 0);
        } catch (IOException | MessagingException | RuntimeException ex) {
            ZohoApiDiagnostics.logTrace(context, traceId, "synthetic_mime",
                    "inventory=partial_or_failed error_class=" + ex.getClass().getSimpleName());
        }
    }

    private static void logZohoMimePart(Context context, String traceId, Part part,
                                        String path, int index, int count, int depth)
            throws IOException, MessagingException {
        if (depth > 32)
            throw new IOException("Zoho synthetic MIME nesting is too deep");

        String contentType = part.getContentType();
        String disposition = part.getDisposition();
        boolean multipart = part.isMimeType("multipart/*");
        Object content = part.getContent();
        long bytes = -1;
        long chars = -1;
        if (!multipart) {
            if (content instanceof String)
                chars = ((String) content).length();
            try (InputStream input = part.getInputStream()) {
                byte[] buffer = new byte[8192];
                long total = 0;
                for (int length = input.read(buffer); length != -1; length = input.read(buffer))
                    total += length;
                bytes = total;
            }
        }

        String classification;
        if (multipart)
            classification = "container";
        else if (part.isMimeType("text/plain"))
            classification = "plain";
        else if (part.isMimeType("text/html"))
            classification = "html";
        else if (isResource(part, disposition))
            classification = "resource";
        else if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || part.getFileName() != null)
            classification = "attachment";
        else
            classification = "other";

        ZohoApiDiagnostics.logTracePart(context, traceId, "synthetic_mime", path,
                index, count, part.getClass().getSimpleName(), contentType, disposition,
                classification, bytes, chars,
                ZohoApiDiagnostics.contentClass(content), false);

        if (multipart && content instanceof Multipart) {
            Multipart children = (Multipart) content;
            for (int i = 0; i < children.getCount(); i++)
                logZohoMimePart(context, traceId, children.getBodyPart(i),
                        path + "/" + i, i, children.getCount(), depth + 1);
        }
    }

    private static boolean isResource(Part part, String disposition)
            throws MessagingException {
        if (Part.INLINE.equalsIgnoreCase(disposition))
            return true;
        String[] ids = part.getHeader("Content-ID");
        return (ids != null && ids.length > 0);
    }

    private static MimeMessage createMessage(Session session, String rawHeaders,
                                             List<Header> headers, String bodyContentType,
                                             String body, List<Attachment> attachments,
                                             boolean zoho)
            throws IOException, MessagingException {
        MimeMessage message;
        if (rawHeaders != null) {
            String raw = rawHeaders;
            if (!raw.endsWith("\n\n") && !raw.endsWith("\r\n\r\n"))
                raw += "\r\n\r\n";
            // Zoho supplies headers separately from decoded body content; the original Content-*
            // fields describe a discarded MIME entity and must not label our synthetic body.
            MimeMessage source = new MimeMessage(session,
                    new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
            message = new MimeMessage(session);
            for (Enumeration<javax.mail.Header> sourceHeaders = source.getAllHeaders(); sourceHeaders.hasMoreElements();) {
                javax.mail.Header header = sourceHeaders.nextElement();
                if (!isEntityHeader(header.getName()))
                    message.addHeader(header.getName(), header.getValue());
            }
        } else {
            message = new MimeMessage(session);
            if (headers != null)
                for (Header header : headers) {
                    if (header == null || header.name == null || header.value == null ||
                            !header.name.matches("[A-Za-z0-9-]+"))
                        continue;
                    if ("content-type".equalsIgnoreCase(header.name) ||
                            "content-transfer-encoding".equalsIgnoreCase(header.name) ||
                            "mime-version".equalsIgnoreCase(header.name))
                        continue;
                    String value = header.value.replaceAll("\\r?\\n[ \\t]+", " ")
                            .replace("\r", " ").replace("\n", " ");
                    message.addHeader(header.name, value);
                }
        }

        List<Attachment> safeAttachments = (attachments == null
                ? new ArrayList<>() : attachments);
        boolean hasBody = (body != null && bodyContentType != null);
        if (!hasBody && safeAttachments.isEmpty()) {
            clearEntityHeaders(message);
            message.setText("", "UTF-8");
            message.setHeader("Content-Type", "text/plain; charset=UTF-8");
            return message;
        }

        MimeMultipart mixed = new MimeMultipart("mixed");
        List<Attachment> inline = new ArrayList<>();
        List<Attachment> ordinary = new ArrayList<>();
        for (Attachment attachment : safeAttachments)
            if (attachment.related || Part.INLINE.equalsIgnoreCase(attachment.disposition) ||
                    attachment.contentId != null)
                inline.add(attachment);
            else
                ordinary.add(attachment);

        if (hasBody && !inline.isEmpty()) {
            MimeMultipart related = new MimeMultipart("related");
            boolean zohoHtmlAlternative = (zoho && isHtmlContentType(bodyContentType));
            related.addBodyPart(createBodyPart(bodyContentType, body, zohoHtmlAlternative));
            for (Attachment attachment : inline)
                related.addBodyPart(createAttachmentPart(attachment));
            MimeBodyPart container = new MimeBodyPart();
            container.setContent(related);
            if (zoho)
                container.setHeader("Content-Type", related.getContentType());
            mixed.addBodyPart(container);
        } else if (hasBody)
            mixed.addBodyPart(createBodyPart(bodyContentType, body));
        else
            ordinary.addAll(inline);

        if (hasBody && inline.isEmpty())
            ordinary.addAll(inline);
        for (Attachment attachment : ordinary)
            mixed.addBodyPart(createAttachmentPart(attachment));
        clearEntityHeaders(message);
        message.setContent(mixed);
        message.setHeader("Content-Type", mixed.getContentType());
        return message;
    }

    private static boolean isEntityHeader(String name) {
        return (name != null && name.regionMatches(true, 0, "Content-", 0, "Content-".length()));
    }

    private static void clearEntityHeaders(MimeMessage message) throws MessagingException {
        for (String name : new String[]{
                "Content-Type", "Content-Transfer-Encoding", "Content-Disposition",
                "Content-ID", "Content-Description", "Content-Language", "Content-Location",
                "Content-MD5"})
            message.removeHeader(name);
    }

    private static MimeBodyPart createBodyPart(String contentType, String body)
            throws IOException, MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String type = withCharset(contentType);
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, type)));
        part.setHeader("Content-Type", type);
        return part;
    }

    private static MimeBodyPart createBodyPart(String contentType, String body,
                                               boolean zohoHtmlAlternative)
            throws IOException, MessagingException {
        if (!zohoHtmlAlternative || !isHtmlContentType(contentType))
            return createBodyPart(contentType, body);

        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText(Jsoup.parse(body).text(), StandardCharsets.UTF_8.name());
        alternative.addBodyPart(plain);
        alternative.addBodyPart(createBodyPart(contentType, body));

        MimeBodyPart root = new MimeBodyPart();
        root.setContent(alternative);
        root.setHeader("Content-Type", alternative.getContentType());
        return root;
    }

    private static boolean isHtmlContentType(String contentType) {
        if (contentType == null)
            return false;
        int separator = contentType.indexOf(';');
        String baseType = (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
        return "text/html".equalsIgnoreCase(baseType);
    }

    private static MimeBodyPart createAttachmentPart(Attachment attachment)
            throws IOException, MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        String type = (attachment.contentType == null || attachment.contentType.trim().isEmpty()
                ? "application/octet-stream" : attachment.contentType);
        byte[] bytes = (attachment.inlineBytes == null ? new byte[0] : attachment.inlineBytes);
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, type)));
        part.setHeader("Content-Type", type);
        if (attachment.name != null && !attachment.name.isEmpty())
            part.setFileName(attachment.name);
        String disposition = (attachment.disposition == null
                ? Part.ATTACHMENT : attachment.disposition);
        part.setDisposition(disposition);
        if (attachment.contentId != null && !attachment.contentId.isEmpty()) {
            String cid = attachment.contentId;
            if (!cid.startsWith("<"))
                cid = "<" + cid;
            if (!cid.endsWith(">"))
                cid += ">";
            part.setHeader("Content-ID", cid);
        }
        // The reserved headers are written only onto synthesized parts; they are never copied
        // from provider-controlled message headers.
        if (attachment.id != null)
            part.setHeader(HEADER_REMOTE_ID, attachment.id);
        if (attachment.folder != null)
            part.setHeader(HEADER_REMOTE_FOLDER, attachment.folder);
        if (attachment.providerType != null)
            part.setHeader(HEADER_REMOTE_TYPE, attachment.providerType);
        if (attachment.size != null && attachment.size >= 0)
            part.setHeader(HEADER_REMOTE_SIZE, Long.toString(attachment.size));
        return part;
    }

    private static String withCharset(String contentType) {
        String type = contentType.trim();
        if (type.toLowerCase(java.util.Locale.ROOT).startsWith("text/") &&
                !type.toLowerCase(java.util.Locale.ROOT).contains("charset="))
            type += "; charset=UTF-8";
        return type;
    }

    static void applyRemoteMetadata(Part part, EntityAttachment attachment)
            throws MessagingException {
        String[] value = part.getHeader(HEADER_REMOTE_ID);
        if (value != null && value.length > 0)
            attachment.provider_id = value[0];
        value = part.getHeader(HEADER_REMOTE_FOLDER);
        if (value != null && value.length > 0)
            attachment.provider_folder = value[0];
        value = part.getHeader(HEADER_REMOTE_TYPE);
        if (value != null && value.length > 0)
            attachment.provider_type = value[0];
        value = part.getHeader(HEADER_REMOTE_SIZE);
        if (value != null && value.length > 0)
            try {
                attachment.size = Long.parseLong(value[0]);
            } catch (NumberFormatException ignored) {
            }
    }

    static String getInternalHeader(Part part, String name) throws MessagingException {
        String[] value = part.getHeader(name);
        return (value == null || value.length == 0 ? null : value[0]);
    }

    static JSONObject jsonObject(JSONObject parent, String key) {
        return (parent == null ? null : parent.optJSONObject(key));
    }
}
