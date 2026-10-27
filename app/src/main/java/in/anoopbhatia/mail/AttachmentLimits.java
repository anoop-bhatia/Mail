package in.anoopbhatia.mail;

/**
 * The attachment ceiling for every native REST provider.
 *
 * <p>Gmail documents 25 MB per attachment and is the strictest of the three (Zoho allows 30 MB per
 * file, Graph serves a 50 MiB MIME body), so the looser local limits made the same file import on
 * one account and fail with "exceeds the local size limit" on another. 25 MB is the ceiling that
 * holds on all three; it bounds attachment bytes, not a whole MIME message, which can carry
 * several attachments and keeps its own larger download bound.</p>
 */
final class AttachmentLimits {
    static final int MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024;

    private AttachmentLimits() {
    }
}
