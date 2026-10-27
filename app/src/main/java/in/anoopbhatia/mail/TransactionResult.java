package in.anoopbhatia.mail;

/**
 * An explicit transaction decision carrying the caller's original return value.
 *
 * <p>Neither null nor false implies rollback: use {@link #rollback(Object)} for
 * a legacy return before its success marker and {@link #commit(Object)} only
 * where the original body marked success. Return expressions must not be moved
 * across that marker if their evaluation can throw or perform work.</p>
 */
public final class TransactionResult<T> {
    final boolean commit;
    final T value;

    private TransactionResult(boolean commit, T value) {
        this.commit = commit;
        this.value = value;
    }

    public static <T> TransactionResult<T> commit(T value) {
        return new TransactionResult<>(true, value);
    }

    public static <T> TransactionResult<T> rollback(T value) {
        return new TransactionResult<>(false, value);
    }
}
