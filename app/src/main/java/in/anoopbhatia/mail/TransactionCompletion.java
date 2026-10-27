package in.anoopbhatia.mail;

import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Decorates Room's public synchronous Callable runner; Room still owns every
 * transaction delimiter, nesting and invalidation operation.
 *
 * <p>The private per-invocation signal skips Room's automatic success marker.
 * It is consumed only after Room's finally has ended the transaction. Any end
 * failure therefore replaces the signal (and the return value), just as in a
 * legacy try/finally. No application throwable is caught, wrapped or suppressed.
 * This relies on the pinned Room compatibility runner's unchanged rethrow;
 * RoomCallableContractTest guards the resolved dependency.</p>
 */
final class TransactionCompletion {
    private TransactionCompletion() {
    }

    static <T, E extends Exception> T call(Function<Callable<T>, T> runner,
                                           Runnable beginFailureCleanup,
                                           TransactionCallable<TransactionResult<T>, E> body)
            throws E {
        Invocation<T, E> invocation = new Invocation<>(body);
        try {
            if (beginFailureCleanup == null)
                return runner.apply(invocation::call);
            return TransactionScope.call(runner, beginFailureCleanup, invocation::call);
        } catch (Rollback signal) {
            if (signal != invocation.rollback)
                throw signal;
            return invocation.rollbackValue;
        }
    }

    /**
     * Internal Callable bridge for independently typed public checked contracts.
     * Like Room's Function runner, this layer declares no checked exception;
     * the public DB adapter declares exactly the callback's three checked types.
     * No application failure is caught, cast, wrapped or narrowed.
     */
    static <T> T callChecked(Function<Callable<T>, T> runner,
                             Runnable beginFailureCleanup,
                             Callable<TransactionResult<T>> body) {
        Invocation<T, Exception> invocation = new Invocation<>(() -> body.call());
        try {
            if (beginFailureCleanup == null)
                return runner.apply(invocation::call);
            // Keep TransactionScope.call's exact pre-callback cleanup window.
            boolean[] entered = {false};
            try {
                return runner.apply(() -> {
                    entered[0] = true;
                    return invocation.call();
                });
            } finally {
                if (!entered[0])
                    beginFailureCleanup.run();
            }
        } catch (Rollback signal) {
            if (signal != invocation.rollback)
                throw signal;
            return invocation.rollbackValue;
        }
    }

    /** State belongs to the decorator, never to a consumer's captured locals. */
    private static final class Invocation<T, E extends Exception> {
        private final TransactionCallable<TransactionResult<T>, E> body;
        private final Rollback rollback = new Rollback();
        private T rollbackValue;

        private Invocation(TransactionCallable<TransactionResult<T>, E> body) {
            this.body = body;
        }

        private T call() throws E {
            TransactionResult<T> result = body.call();
            if (result.commit)
                return result.value;
            rollbackValue = result.value;
            throw rollback;
        }
    }

    /** Control flow only: not shared, not exposed, and never an exception wrapper. */
    private static final class Rollback extends RuntimeException {
        private Rollback() {
            super(null, null, false, false);
        }
    }
}
