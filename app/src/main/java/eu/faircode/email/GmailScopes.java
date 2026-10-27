package eu.faircode.email;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** The exact OAuth grants required by FairEmail's native Gmail REST flow. */
final class GmailScopes {
    static final String READ = "https://www.googleapis.com/auth/gmail.readonly";
    static final String MODIFY = "https://www.googleapis.com/auth/gmail.modify";
    static final String SEND = "https://www.googleapis.com/auth/gmail.send";
    // Restricted broad scope requested only for permanent users.messages.delete access.
    static final String PERMANENT_DELETE = "https://mail.google.com/";

    private static final List<String> REQUESTED = Collections.unmodifiableList(
            Arrays.asList(READ, MODIFY, SEND, PERMANENT_DELETE));

    private GmailScopes() {
    }

    static List<String> getRequested() {
        return REQUESTED;
    }

    static boolean hasRequired(Collection<String> granted) {
        return (granted != null && granted.containsAll(REQUESTED));
    }
}
