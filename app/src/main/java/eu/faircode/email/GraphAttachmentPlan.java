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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

/** Identifies Graph large-file attachments without rewriting protected MIME structures. */
final class GraphAttachmentPlan {
    static final long LARGE_ATTACHMENT_THRESHOLD = 3L * 1024L * 1024L;
    static final long MAX_ATTACHMENT_SIZE = 150L * 1024L * 1024L;

    static final class Attachment {
        final File file;
        final long size;
        final String name;
        final String contentType;
        final String contentId;
        final boolean inline;

        Attachment(File file, long size, String name, String contentType,
                   String contentId, boolean inline) {
            this.file = file;
            this.size = size;
            this.name = name;
            this.contentType = contentType;
            this.contentId = contentId;
            this.inline = inline;
        }
    }

    static final class Plan {
        final List<Attachment> attachments;
        final long totalSize;
        final boolean directFallback;

        Plan(List<Attachment> attachments, long totalSize, boolean directFallback) {
            this.attachments = Collections.unmodifiableList(new ArrayList<>(attachments));
            this.totalSize = totalSize;
            this.directFallback = directFallback;
        }

        boolean requiresUploadSessions() {
            return !attachments.isEmpty();
        }
    }

    private static final class Removal {
        final Multipart parent;
        final BodyPart part;
        final Attachment attachment;

        Removal(Multipart parent, BodyPart part, Attachment attachment) {
            this.parent = parent;
            this.part = part;
            this.attachment = attachment;
        }
    }

    private static final class Inspection {
        final List<Removal> large = new ArrayList<>();
        boolean protectedMime;
        boolean unsupported;
    }

    private GraphAttachmentPlan() {
    }

    static boolean usesUploadSession(long size) {
        return (size >= LARGE_ATTACHMENT_THRESHOLD);
    }

    /**
     * Return a plan for files that can be represented as Graph file attachments.
     * Only those file parts are removed from the MIME draft; all remaining message
     * headers, body alternatives, small attachments, and MIME structure are retained.
     * Signed/encrypted MIME, non-file data sources, and incomplete metadata fall back
     * to the original sendMail path instead of being approximated.
     */
    static Plan prepare(MimeMessage message) throws IOException, MessagingException {
        Inspection inspection = new Inspection();
        inspect(message, null, inspection);
        if (inspection.large.isEmpty())
            return new Plan(Collections.emptyList(), 0, false);

        if (inspection.protectedMime || inspection.unsupported)
            return new Plan(Collections.emptyList(), 0, true);

        long total = 0;
        List<Attachment> attachments = new ArrayList<>();
        for (Removal removal : inspection.large) {
            Attachment attachment = removal.attachment;
            if (attachment.size > MAX_ATTACHMENT_SIZE)
                throw new IllegalArgumentException("Microsoft Graph supports individual attachments up to 150 MB");
            if (attachment.size > Long.MAX_VALUE - total)
                throw new IllegalArgumentException("Microsoft Graph attachment size overflow");
            if (removal.parent != null && !(removal.parent instanceof MimeMultipart))
                return new Plan(Collections.emptyList(), 0, true);
            total += attachment.size;
            attachments.add(attachment);
        }

        // All candidates are validated before any MIME part is removed.
        for (Removal removal : inspection.large)
            if (!removal.parent.removeBodyPart(removal.part))
                throw new MessagingException("Unable to prepare Microsoft Graph attachment draft");
        message.saveChanges();

        return new Plan(attachments, total, false);
    }

    private static void inspect(Part part, Multipart parent, Inspection inspection)
            throws IOException, MessagingException {
        if (isProtectedMimeType(part.getContentType())) {
            inspection.protectedMime = true;
        }

        if (part instanceof BodyPart && isAttachment(part)) {
            DataSource source = ((BodyPart) part).getDataHandler().getDataSource();
            if (source instanceof FileDataSource) {
                File file = ((FileDataSource) source).getFile();
                if (!file.isFile() || !file.canRead()) {
                    inspection.unsupported = true;
                    return;
                }
                long size = file.length();
                if (usesUploadSession(size)) {
                    String name = part.getFileName();
                    String contentType = part.getContentType();
                    String contentId = getContentId(part);
                    String disposition = part.getDisposition();
                    if (name == null || name.trim().length() == 0 ||
                            contentType == null || contentType.trim().length() == 0 ||
                            parent == null) {
                        inspection.unsupported = true;
                        return;
                    }
                    boolean inline = Part.INLINE.equalsIgnoreCase(disposition) || contentId != null;
                    inspection.large.add(new Removal(parent, (BodyPart) part,
                            new Attachment(file, size, name, contentType,
                                    contentId, inline)));
                }
                return;
            }
        }

        Object content = part.getContent();
        if (content instanceof Multipart) {
            Multipart multipart = (Multipart) content;
            if (isProtectedMimeType(multipart.getContentType()))
                inspection.protectedMime = true;
            for (int index = 0; index < multipart.getCount(); index++)
                inspect(multipart.getBodyPart(index), multipart, inspection);
        }
    }

    private static boolean isAttachment(Part part) throws MessagingException {
        String name = part.getFileName();
        String disposition = part.getDisposition();
        String contentId = getContentId(part);
        return (name != null || Part.ATTACHMENT.equalsIgnoreCase(disposition) ||
                Part.INLINE.equalsIgnoreCase(disposition) || contentId != null);
    }

    private static String getContentId(Part part) throws MessagingException {
        String[] values = part.getHeader("Content-ID");
        if (values == null || values.length == 0 || values[0] == null)
            return null;
        String value = values[0].trim();
        if (value.length() >= 2 && value.charAt(0) == '<' && value.charAt(value.length() - 1) == '>')
            value = value.substring(1, value.length() - 1).trim();
        return (value.length() == 0 ? null : value);
    }

    private static boolean isProtectedMimeType(String value) {
        if (value == null)
            return false;
        String type = value.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return ("multipart/signed".equals(type) || "multipart/encrypted".equals(type) ||
                "application/pkcs7-mime".equals(type) || "application/x-pkcs7-mime".equals(type) ||
                "application/pkcs7-signature".equals(type) || "application/x-pkcs7-signature".equals(type) ||
                "application/pgp-encrypted".equals(type) || "application/pgp-signature".equals(type));
    }
}
