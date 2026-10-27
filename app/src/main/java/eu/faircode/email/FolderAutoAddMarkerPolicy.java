package eu.faircode.email;

/** Presentation policy for the debug-only Sent-folder auto-add marker. */
final class FolderAutoAddMarkerPolicy {
    private FolderAutoAddMarkerPolicy() {
    }

    static boolean shouldShow(boolean debug, String folderType, Boolean autoAdd, Integer accountAuthType) {
        if (!debug || !EntityFolder.SENT.equals(folderType) || Boolean.FALSE.equals(autoAdd))
            return false;

        // Native REST providers do not use the legacy automatic Sent-copy setting represented by this marker.
        return !isNativeRestAuthType(accountAuthType);
    }

    private static boolean isNativeRestAuthType(Integer authType) {
        return (authType != null &&
                (authType == ServiceAuthenticator.AUTH_TYPE_GMAIL ||
                        authType == ServiceAuthenticator.AUTH_TYPE_ZOHO ||
                        authType == ServiceAuthenticator.AUTH_TYPE_GRAPH_API));
    }
}
