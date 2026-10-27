package in.anoopbhatia.mail;

/** Policy for distinguishing user-triggered thread auto-close from passive data updates. */
final class ThreadAutoClosePolicy {
    private ThreadAutoClosePolicy() {
    }

    static boolean hasUserAction(boolean hasTargets, boolean hasRemovedMessages) {
        return hasTargets || hasRemovedMessages;
    }

    static boolean shouldCloseEmptyThread(boolean autoClose, boolean hasOnClose, boolean hasUserAction) {
        return hasUserAction && (autoClose || hasOnClose);
    }

    static boolean shouldEvaluateFolderActionClose(boolean autoClose, boolean hasOnClose,
                                                   boolean hasUserAction, int previousCount) {
        return hasUserAction && previousCount > 0 && (autoClose || hasOnClose);
    }
}
