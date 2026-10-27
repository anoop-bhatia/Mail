package in.anoopbhatia.mail;

/**
 * Timeout policy for Zoho Mail REST requests.
 *
 * <p>Read timeouts are per-read stall budgets, not total transfer deadlines: the clock restarts
 * whenever bytes arrive, so the budget is sized by how long Zoho can legitimately stay quiet while
 * it assembles a response, not by body length. A send gets the longest budget because a send cut
 * short after its body was transmitted cannot be replayed, only reconciled. Attachment and inline
 * downloads keep their own upload/download policy in {@link ZohoUploadTimeouts}.</p>
 */
final class ZohoTimeouts {
    static final int CONNECT_MILLIS = 20_000;

    /** Control-plane JSON: folders, message lists, details, flags, header probes. */
    static final int CONTROL_READ_MILLIS = 30_000;

    /** Whole message content and original MIME, which Zoho assembles before answering. */
    static final int BODY_READ_MILLIS = 60_000;

    /** Sending is the one request whose acceptance must not be guessed from a short stall. */
    static final int SEND_READ_MILLIS = 90_000;

    private ZohoTimeouts() {
    }

    static int readTimeoutMillis(boolean send, boolean bodyRead) {
        if (send)
            return SEND_READ_MILLIS;
        return (bodyRead ? BODY_READ_MILLIS : CONTROL_READ_MILLIS);
    }
}
