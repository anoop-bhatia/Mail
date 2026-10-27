package in.anoopbhatia.mail;

import java.util.List;

/** Queue-safety helpers for Zoho sends whose request may have been accepted without a usable response. */
final class ZohoSendAttempt {
    private ZohoSendAttempt() {
    }

    static boolean isOutcomeUnknown(Throwable ex) {
        return (ex instanceof ZohoApi.SendOutcomeUnknownException);
    }

    static <T> boolean continueQueueAfterFailure(List<T> operations, T operation, Throwable ex) {
        if (!isOutcomeUnknown(ex))
            return false;
        for (int index = 0; index < operations.size(); index++)
            if (operations.get(index) == operation) {
                operations.remove(index);
                break;
            }
        return true;
    }
}
