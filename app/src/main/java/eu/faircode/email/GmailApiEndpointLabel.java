package eu.faircode.email;

/** Formats endpoint text for account and identity list rows without changing stored endpoints. */
final class GmailApiEndpointLabel {
    private GmailApiEndpointLabel() {
    }

    static String format(boolean gmailApi, String apiLabel, String host, int port,
                         String encryption) {
        return (gmailApi
                ? apiLabel
                : String.format("%s:%d/%s", host, port, encryption));
    }
}
