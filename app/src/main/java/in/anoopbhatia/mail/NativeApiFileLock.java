package in.anoopbhatia.mail;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Coordinates native cache publication (including database availability flags) with cleanup. */
final class NativeApiFileLock {
    // Downloads may run concurrently, but cleanup must not inspect/delete either their
    // staging files or a published file whose database flag has not been committed yet.
    // Fairness prevents a stream of downloads from starving a waiting manual cleanup.
    // Acquire before opening a database transaction, never while holding one.
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    private NativeApiFileLock() {
    }

    static Guard fetch() {
        return new Guard(lock.readLock());
    }

    static Guard cleanup() {
        return new Guard(lock.writeLock());
    }

    static final class Guard implements AutoCloseable {
        private final Lock held;

        private Guard(Lock held) {
            this.held = held;
            held.lock();
        }

        @Override
        public void close() {
            held.unlock();
        }
    }
}
