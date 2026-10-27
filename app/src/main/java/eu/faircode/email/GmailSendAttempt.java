package eu.faircode.email;

import java.util.List;

/** Queue-safety helpers for Gmail sends whose request may have reached Gmail without a response. */
final class GmailSendAttempt {
    private GmailSendAttempt() {
    }

    static boolean isOutcomeUnknown(Throwable ex) {
        return (ex instanceof GmailApi.SendOutcomeUnknownException);
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
