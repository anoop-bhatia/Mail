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

import java.io.IOException;
import java.util.Locale;

import javax.activation.DataHandler;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Converts Gmail's structured `format=full` payload to MIME while keeping ordinary attachments lazy. */
final class GmailApiMessageParser {
    static final String ATTACHMENT_ID = "gmail-attachment-id";

    interface AttachmentDataFetcher {
        byte[] fetch(String attachmentId) throws IOException, JSONException;
    }

    static final class Result {
        final MimeMessage mimeMessage;
        final boolean bodyComplete;
        final int bodyParts;
        final int incompleteBodyParts;
        final int fetchedBodyParts;
        final int attachmentParts;
        final int attachmentIdParts;
        final int inlineDataAttachmentParts;

        Result(MimeMessage mimeMessage, boolean bodyComplete, ParseState state) {
            this.mimeMessage = mimeMessage;
            this.bodyComplete = bodyComplete;
            this.bodyParts = state.bodyParts;
            this.incompleteBodyParts = state.incompleteBodyParts;
            this.fetchedBodyParts = state.fetchedBodyParts;
            this.attachmentParts = state.attachmentParts;
            this.attachmentIdParts = state.attachmentIdParts;
            this.inlineDataAttachmentParts = state.inlineDataAttachmentParts;
        }
    }

    private static final class ParseState {
        int bodyParts;
        int incompleteBodyParts;
        int fetchedBodyParts;
        int attachmentParts;
        int attachmentIdParts;
        int inlineDataAttachmentParts;
    }

    private GmailApiMessageParser() {
    }

    static MimeMessage parse(Session session, JSONObject json, boolean includeBody,
                             GmailApi.Base64UrlDecoder decoder)
            throws IOException, MessagingException, JSONException {
        return parseDetailed(session, json, includeBody, decoder, null).mimeMessage;
    }

    static Result parseDetailed(Session session, JSONObject json, boolean includeBody,
                                GmailApi.Base64UrlDecoder decoder,
                                AttachmentDataFetcher attachmentDataFetcher)
            throws IOException, MessagingException, JSONException {
        JSONObject payload = json.optJSONObject("payload");
        if (payload == null)
            throw new IOException("Gmail full message response did not contain MIME payload");
        ParseState state = new ParseState();
        MimeMessage message = new MimeMessage(session);
        addHeaders(message, payload.optJSONArray("headers"));
        MimeBodyPart root = toPart(payload, includeBody, decoder, attachmentDataFetcher, state);
        Object content = root.getContent();
        if (content instanceof MimeMultipart) {
            message.setContent((MimeMultipart) content);
            // setContent(Multipart) installs the data handler but does not reliably
            // publish the MIME type until saveChanges; import walks the live tree now.
            message.setHeader("Content-Type", root.getContentType());
        } else {
            message.setDataHandler(root.getDataHandler());
            String[] type = root.getHeader("Content-Type");
            if (type != null && type.length > 0)
                message.setHeader("Content-Type", type[0]);
            String[] disposition = root.getHeader("Content-Disposition");
            if (disposition != null && disposition.length > 0)
                message.setHeader("Content-Disposition", disposition[0]);
        }
        return new Result(message, includeBody && state.incompleteBodyParts == 0, state);
    }

    private static void addHeaders(MimeMessage message, JSONArray headers)
            throws JSONException, MessagingException {
        if (headers == null)
            return;
        for (int i = 0; i < headers.length(); i++) {
            JSONObject item = headers.optJSONObject(i);
            if (item == null)
                continue;
            String name = item.optString("name", null);
            String value = item.optString("value", null);
            if (name == null || value == null || !name.matches("[A-Za-z0-9-]+"))
                continue;
            if ("content-type".equalsIgnoreCase(name) ||
                    "content-transfer-encoding".equalsIgnoreCase(name) ||
                    "mime-version".equalsIgnoreCase(name))
                continue;
            message.addHeader(name, value.replace("\r", " ").replace("\n", " "));
        }
    }

    private static MimeBodyPart toPart(JSONObject source, boolean includeBody,
                                       GmailApi.Base64UrlDecoder decoder,
                                       AttachmentDataFetcher attachmentDataFetcher,
                                       ParseState state)
            throws IOException, MessagingException, JSONException {
        String mimeType = source.optString("mimeType", "application/octet-stream");
        String filename = source.optString("filename", null);
        JSONArray children = source.optJSONArray("parts");
        if (children != null && children.length() > 0) {
            String subtype = "mixed";
            int slash = mimeType.indexOf('/');
            if (slash >= 0 && slash + 1 < mimeType.length())
                subtype = mimeType.substring(slash + 1).toLowerCase(Locale.ROOT);
            MimeMultipart multipart = new MimeMultipart(subtype);
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                if (child != null)
                    multipart.addBodyPart(toPart(child, includeBody, decoder,
                            attachmentDataFetcher, state));
            }
            MimeBodyPart container = new MimeBodyPart();
            container.setContent(multipart);
            container.setHeader("Content-Type", multipart.getContentType());
            return container;
        }

        JSONObject body = source.optJSONObject("body");
        String attachmentId = (body == null ? null : body.optString("attachmentId", null));
        if (attachmentId != null && attachmentId.isEmpty())
            attachmentId = null;
        String partId = source.optString("partId", null);
        String encoded = (body == null ? null : body.optString("data", null));
        boolean hasEncodedData = (body != null && body.has("data") && !body.isNull("data"));
        long expectedSize = (body == null ? -1 : body.optLong("size", -1));
        JSONArray headers = source.optJSONArray("headers");
        String disposition = getHeader(headers, "Content-Disposition");
        String contentId = getHeader(headers, "Content-ID");
        boolean readableBody = isReadableBody(mimeType, filename, disposition);
        byte[] bytes = new byte[0];

        if (includeBody && readableBody) {
            state.bodyParts++;
            boolean bodyBytesPresent = false;
            if (attachmentId != null) {
                // Gmail can store even a text/plain or text/html body part separately. Treat its
                // attachmentId as a body locator, not as evidence that the message body is complete.
                if (attachmentDataFetcher != null) {
                    bytes = attachmentDataFetcher.fetch(attachmentId);
                    if (bytes == null)
                        bytes = new byte[0];
                    state.fetchedBodyParts++;
                    bodyBytesPresent = true;
                }
            } else if (hasEncodedData) {
                bytes = decode(encoded, decoder);
                bodyBytesPresent = true;
            } else if (expectedSize == 0) {
                // Gmail may omit data for a legitimately empty text part.
                bodyBytesPresent = true;
            }

            if (!bodyBytesPresent || expectedSize < 0 || bytes.length != expectedSize)
                state.incompleteBodyParts++;
        } else if (includeBody && attachmentId == null && hasEncodedData) {
            // Gmail may inline no-ID MIME attachments in body.data. Preserve those bytes in the
            // MIME tree, while ordinary attachment file installation remains a separate policy.
            bytes = decode(encoded, decoder);
            if (bytes.length > 0)
                state.inlineDataAttachmentParts++;
        }

        MimeBodyPart part = new MimeBodyPart();
        String type = getHeader(headers, "Content-Type");
        if (type == null || type.trim().isEmpty())
            type = mimeType;
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, type)));
        part.setHeader("Content-Type", type);
        setHeader(part, headers, "Content-Disposition", null);
        setHeader(part, headers, "Content-ID", null);
        if (filename != null && !filename.isEmpty())
            part.setFileName(filename);

        boolean attachment = (!readableBody && (attachmentId != null ||
                (filename != null && !filename.isEmpty()) || disposition != null || contentId != null ||
                !mimeType.toLowerCase(Locale.ROOT).startsWith("text/")));
        if (attachment) {
            state.attachmentParts++;
            if (attachmentId != null)
                state.attachmentIdParts++;
            if (disposition == null)
                part.setDisposition(javax.mail.Part.ATTACHMENT);
            String remoteId = (attachmentId == null ? partId : attachmentId);
            String remoteType = (attachmentId == null ? NativeApiContent.GMAIL_INLINE_PART : ATTACHMENT_ID);
            if (remoteId != null)
                part.setHeader(NativeApiContent.HEADER_REMOTE_ID, remoteId);
            part.setHeader(NativeApiContent.HEADER_REMOTE_TYPE, remoteType);
            if (attachmentId == null && partId != null)
                part.setHeader(NativeApiContent.HEADER_GMAIL_PART_ID, partId);
            if (expectedSize >= 0)
                part.setHeader(NativeApiContent.HEADER_REMOTE_SIZE, Long.toString(expectedSize));
        }
        return part;
    }

    private static boolean isReadableBody(String mimeType, String filename, String disposition) {
        if (mimeType == null || !(mimeType.equalsIgnoreCase("text/plain") ||
                mimeType.equalsIgnoreCase("text/html")))
            return false;
        if (filename != null && !filename.isEmpty())
            return false;
        if (disposition == null)
            return true;
        int semicolon = disposition.indexOf(';');
        String value = (semicolon < 0 ? disposition : disposition.substring(0, semicolon)).trim();
        return !javax.mail.Part.ATTACHMENT.equalsIgnoreCase(value);
    }

    private static byte[] decode(String encoded, GmailApi.Base64UrlDecoder decoder) throws IOException {
        try {
            return decoder.decode(encoded == null ? "" : encoded);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid Gmail base64url MIME part", ex);
        }
    }

    private static void setHeader(MimeBodyPart part, JSONArray headers, String name, String fallback)
            throws JSONException, MessagingException {
        String value = getHeader(headers, name);
        if (value == null)
            value = fallback;
        if (value != null && !value.isEmpty())
            part.setHeader(name, value.replace("\r", " ").replace("\n", " "));
    }

    private static String getHeader(JSONArray headers, String name) throws JSONException {
        if (headers == null)
            return null;
        for (int i = 0; i < headers.length(); i++) {
            JSONObject item = headers.optJSONObject(i);
            if (item != null && name.equalsIgnoreCase(item.optString("name")))
                return item.optString("value", null);
        }
        return null;
    }
}
