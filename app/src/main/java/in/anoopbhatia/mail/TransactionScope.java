package in.anoopbhatia.mail;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/** Coordinates cleanup for the small window before a synchronous Room runner enters its callback. */
final class TransactionScope {
    private TransactionScope() {
    }

    static void run(Consumer<Runnable> runner, Runnable cleanup, Runnable body) {
        boolean[] entered = {false};
        try {
            runner.accept(() -> {
                entered[0] = true;
                body.run();
            });
        } finally {
            if (!entered[0])
                cleanup.run();
        }
    }

    static <T, E extends Exception> T call(Function<Callable<T>, T> runner,
                                           Runnable cleanup,
                                           TransactionCallable<T, E> body)
            throws E {
        boolean[] entered = {false};
        try {
            return runner.apply(() -> {
                entered[0] = true;
                return body.call();
            });
        } finally {
            if (!entered[0])
                cleanup.run();
        }
    }

}
