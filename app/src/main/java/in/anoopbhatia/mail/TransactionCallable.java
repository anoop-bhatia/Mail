package in.anoopbhatia.mail;

/**
 * A typed body for a fully committing synchronous Room transaction.
 *
 * <p>Normal return, including null or false, commits. A legacy return before
 * its success marker is therefore not eligible. The pinned Room Callable
 * runner transports checked exceptions unchanged; the dependency contract
 * test guards that behavior without wrapping or unchecked rethrow tricks.
 * Unrelated checked types may infer Exception and must not be accommodated
 * by broadening a caller's existing throws contract.</p>
 */
@FunctionalInterface
public interface TransactionCallable<T, E extends Exception> {
    T call() throws E;
}
