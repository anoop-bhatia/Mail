package in.anoopbhatia.mail;

import java.util.Collection;
import java.util.List;

/** The exact OAuth grants required by Mail's native Gmail REST flow. */
final class GmailScopes {
    private static final List<String> REQUESTED = ApiCredentials.get("gmail").scopes;
    static final String READ = REQUESTED.get(0);
    static final String MODIFY = REQUESTED.get(1);
    static final String SEND = REQUESTED.get(2);
    // Restricted broad scope requested only for permanent users.messages.delete access.
    static final String PERMANENT_DELETE = REQUESTED.get(3);

    private GmailScopes() {
    }

    static List<String> getRequested() {
        return REQUESTED;
    }

    static boolean hasRequired(Collection<String> granted) {
        return (granted != null && granted.containsAll(REQUESTED));
    }
}
