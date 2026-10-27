package in.anoopbhatia.mail;

/**
 * Process-local, opaque identities for synchronization ownership and diagnostics.
 *
 * These are not JVM thread IDs or Linux TIDs. Every participating thread keeps
 * one positive identity for its lifetime, including across executor tasks and
 * renames. Identities are never reused within a process. A plain ThreadLocal
 * (not an InheritableThreadLocal) prevents children from sharing ownership, and
 * does not require a global map retaining finished threads.
 */
final class ThreadIdentity {
    private static final Sequence SEQUENCE = new Sequence(0);
    private static final ThreadLocal<Long> CURRENT = ThreadLocal.withInitial(SEQUENCE::next);

    private ThreadIdentity() {
    }

    static long current() {
        return CURRENT.get();
    }

    /** Allocation is serialized only on a thread's first identity lookup. */
    static final class Sequence {
        private long last;

        Sequence(long last) {
            if (last < 0)
                throw new IllegalArgumentException("Negative thread identity sequence");
            this.last = last;
        }

        synchronized long next() {
            // Do not wrap into a previously issued identity, even at exhaustion.
            if (last == Long.MAX_VALUE)
                throw new IllegalStateException("Thread identity sequence exhausted");
            return ++last;
        }
    }
}
