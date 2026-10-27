package in.anoopbhatia.mail;

/**
 * Timeout policy for Gmail REST requests.
 *
 * <p>The read timeout is a per-read stall budget, not a total transfer deadline: it bounds how
 * long a single socket read may stay silent, and the clock restarts whenever bytes arrive. It is
 * therefore sized by how long Gmail can legitimately stay quiet — server-side assembly of a raw
 * MIME message or a large attachment delays the first bytes — and not by body length.</p>
 */
final class GmailTimeouts {
    static final int CONNECT_MILLIS = 20_000;

    /** Control-plane JSON: labels, profile, message lists, metadata, history pages, mutations. */
    static final int SMALL_READ_MILLIS = 30_000;

    /** Message, raw MIME and attachment bodies, which Gmail assembles before answering. */
    static final int BODY_READ_MILLIS = 60_000;

    /** Sending is the one request whose acceptance must not be guessed from a short stall. */
    static final int SEND_READ_MILLIS = 90_000;

    /** A response allowed to exceed this size is a body transfer, not control-plane JSON. */
    static final int BODY_THRESHOLD_BYTES = 1024 * 1024;

    private GmailTimeouts() {
    }

    static int readTimeoutMillis(int maxResponseBytes) {
        if (maxResponseBytes < 0)
            throw new IllegalArgumentException("Negative Gmail response size limit");
        return (maxResponseBytes > BODY_THRESHOLD_BYTES ? BODY_READ_MILLIS : SMALL_READ_MILLIS);
    }
}
