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

package eu.faircode.email;

import java.io.IOException;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

/** Enforces Graph's draft -> attachment uploads -> send ordering without replaying send. */
final class GraphDraftFlow {
    interface Transport extends GraphAttachmentUpload.Transport {
        String createDraft(MimeMessage message) throws IOException, MessagingException;

        default String createReplyDraft(String sourceMessageId, MimeMessage message)
                throws IOException, MessagingException {
            return createDraft(message);
        }

        void uploadAttachment(String messageId, GraphAttachmentPlan.Attachment attachment) throws IOException;

        void sendDraft(String messageId) throws IOException;

        void deleteDraft(String messageId) throws IOException;

        default boolean isCancelled() {
            return Thread.currentThread().isInterrupted();
        }
    }

    private GraphDraftFlow() {
    }

    static void send(Transport transport, MimeMessage message, GraphAttachmentPlan.Plan plan)
            throws IOException, MessagingException {
        send(transport, null, message, plan);
    }

    static void send(Transport transport, String sourceMessageId,
                     MimeMessage message, GraphAttachmentPlan.Plan plan)
            throws IOException, MessagingException {
        if (plan == null || !plan.requiresUploadSessions())
            throw new IllegalArgumentException("Microsoft Graph draft flow requires large attachments");

        String draftId = (sourceMessageId == null || sourceMessageId.trim().isEmpty()
                ? transport.createDraft(message) : transport.createReplyDraft(sourceMessageId, message));
        boolean sendStarted = false;
        try {
            for (GraphAttachmentPlan.Attachment attachment : plan.attachments)
                transport.uploadAttachment(draftId, attachment);

            // From here on, a missing send response is ambiguous; never replay it in this flow.
            sendStarted = true;
            transport.sendDraft(draftId);
        } catch (IOException | RuntimeException ex) {
            if (!sendStarted && !transport.isCancelled())
                try {
                    transport.deleteDraft(draftId);
                } catch (IOException ignored) {
                    // Best-effort cleanup only; do not obscure the original failure.
                }
            throw ex;
        }
    }
}
