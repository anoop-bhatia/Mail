package eu.faircode.email;

/** Privacy-safe counters for one bounded Zoho REST message-list page. */
final class ZohoPageStats {
    boolean listAttempted;
    int listStatus = -1;
    int listItems;
    int knownReferences;
    int newReferences;
    int metadataGetSuccesses;
    int metadataGetFailures;
    int linkedExisting;
    int imported;

    void markListAttempted() {
        listAttempted = true;
    }

    void recordListResult(int httpStatus, int itemCount) {
        listStatus = httpStatus;
        listItems = Math.max(0, itemCount);
    }

    void recordListStatus(int httpStatus) {
        if (listAttempted)
            listStatus = httpStatus;
    }

    void recordReference(boolean locallyKnown) {
        if (locallyKnown)
            knownReferences++;
        else
            newReferences++;
    }

    void recordMetadataGet(boolean succeeded) {
        if (succeeded)
            metadataGetSuccesses++;
        else
            metadataGetFailures++;
    }

    void recordLinkedExisting() {
        linkedExisting++;
    }

    void recordImported() {
        imported++;
    }

    String toDiagnosticString(String pageType) {
        String status = (!listAttempted ? "not-attempted" :
                (listStatus < 0 ? "unavailable" : Integer.toString(listStatus)));
        return "Zoho REST page=" + pageType +
                " listAttempted=" + listAttempted +
                " listStatus=" + status +
                " listItems=" + listItems +
                " refsKnown=" + knownReferences +
                " refsNew=" + newReferences +
                " metadataGetSuccess=" + metadataGetSuccesses +
                " metadataGetFailure=" + metadataGetFailures +
                " linkedExisting=" + linkedExisting +
                " imported=" + imported;
    }
}
