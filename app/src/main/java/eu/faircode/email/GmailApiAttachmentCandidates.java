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
import java.util.ArrayList;
import java.util.List;

import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.internet.ContentType;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.ParseException;
import javax.mail.internet.MimeUtility;

/** Finds Gmail REST metadata parts independently of filename and disposition heuristics. */
final class GmailApiAttachmentCandidates {
    static final class Candidate {
        final Part part;
        final boolean related;

        Candidate(Part part, boolean related) {
            this.part = part;
            this.related = related;
        }
    }

    private GmailApiAttachmentCandidates() {
    }

    static List<Candidate> collect(MimeMessage message) throws IOException, MessagingException {
        List<Candidate> result = new ArrayList<>();
        collect(message, false, result);
        return result;
    }

    private static void collect(Part part, boolean inRelated, List<Candidate> result)
            throws IOException, MessagingException {
        if (part.isMimeType("multipart/*")) {
            Object content = part.getContent();
            if (content instanceof Multipart) {
                Multipart multipart = (Multipart) content;
                boolean childRelated = inRelated || part.isMimeType("multipart/related");
                for (int i = 0; i < multipart.getCount(); i++)
                    collect(multipart.getBodyPart(i), childRelated, result);
                return;
            }
        }

        String providerType = NativeApiContent.getInternalHeader(
                part, NativeApiContent.HEADER_REMOTE_TYPE);
        String providerId = NativeApiContent.getInternalHeader(
                part, NativeApiContent.HEADER_REMOTE_ID);
        if (providerId == null || providerId.isEmpty() ||
                !(GmailApiMessageParser.ATTACHMENT_ID.equals(providerType) ||
                        NativeApiContent.GMAIL_INLINE_PART.equals(providerType)))
            return;

        result.add(new Candidate(part, inRelated));
    }

    static EntityAttachment toEntityAttachment(Candidate candidate) throws MessagingException {
        Part part = candidate.part;
        EntityAttachment attachment = new EntityAttachment();
        String filename = part.getFileName();
        if (filename != null && !filename.isEmpty())
            attachment.name = filename;
        try {
            attachment.type = new ContentType(part.getContentType()).getBaseType()
                    .toLowerCase(java.util.Locale.ROOT);
        } catch (ParseException ex) {
            attachment.type = "application/octet-stream";
        }
        attachment.disposition = part.getDisposition();
        if (attachment.disposition == null)
            attachment.disposition = Part.ATTACHMENT;
        String[] contentIds = part.getHeader("Content-ID");
        if (contentIds != null && contentIds.length > 0) {
            String cid = MimeUtility.unfold(contentIds[0]);
            if (cid != null && !cid.isEmpty()) {
                if (!cid.startsWith("<"))
                    cid = "<" + cid;
                if (!cid.endsWith(">"))
                    cid += ">";
                attachment.cid = cid;
            }
        }
        if (candidate.related)
            attachment.related = true;
        String size = NativeApiContent.getInternalHeader(part, NativeApiContent.HEADER_REMOTE_SIZE);
        if (size != null)
            try {
                long value = Long.parseLong(size);
                if (value >= 0)
                    attachment.size = value;
            } catch (NumberFormatException ignored) {
            }
        if (attachment.size == null) {
            int value = part.getSize();
            if (value > 0)
                attachment.size = (long) value;
        }
        NativeApiContent.applyRemoteMetadata(part, attachment);
        return attachment;
    }
}
