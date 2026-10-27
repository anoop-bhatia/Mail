package eu.faircode.email;

import java.util.Collections;
import java.util.Locale;

/** Maps Microsoft Graph folder names to FairEmail system-folder types. */
final class GraphFolderDefaults {
    private GraphFolderDefaults() {
    }

    static String getFolderType(String remoteName) {
        String name = (remoteName == null ? "" : remoteName.trim().toLowerCase(Locale.ROOT));
        switch (name) {
            case "inbox":
            case "boîte de réception":
            case "posteingang":
            case "bandeja de entrada":
                return EntityFolder.INBOX;
            case "drafts":
            case "entwürfe":
            case "brouillons":
                return EntityFolder.DRAFTS;
            case "sent":
            case "sent items":
            case "sent mail":
            case "gesendete elemente":
            case "éléments envoyés":
                return EntityFolder.SENT;
            case "trash":
            case "deleted items":
            case "deleted messages":
            case "papierkorb":
            case "éléments supprimés":
                return EntityFolder.TRASH;
            case "junk email":
            case "junk e-mail":
            case "spam":
            case "junk":
            case "courrier indésirable":
                return EntityFolder.JUNK;
            case "archive":
            case "archives":
                return EntityFolder.ARCHIVE;
            default:
                EntityFolder inferred = new EntityFolder(remoteName, EntityFolder.USER);
                EntityFolder.guessTypes(Collections.singletonList(inferred), "graph.microsoft.com");
                return inferred.type;
        }
    }
}
