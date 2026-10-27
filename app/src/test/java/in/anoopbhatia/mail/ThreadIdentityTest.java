package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class ThreadIdentityTest {
    private static final long TIMEOUT_SECONDS = 5;

    @Test
    public void identityIsPositiveAndStableAcrossTasksAndRenames() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<long[]> first = executor.submit(() -> {
                long before = ThreadIdentity.current();
                Thread.currentThread().setName("identity-renamed");
                long after = ThreadIdentity.current();
                return new long[] {before, after};
            });
            Future<Long> second = executor.submit(ThreadIdentity::current);
            long[] values = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long later = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(values[0] > 0);
            assertEquals(values[0], values[1]);
            assertEquals(values[0], later);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    public void childThreadDoesNotInheritParentIdentity() throws Exception {
        long parent = ThreadIdentity.current();
        FutureTask<Long> childTask = new FutureTask<>(ThreadIdentity::current);
        Thread child = new Thread(childTask, "identity-child");
        child.start();
        long childId = childTask.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        child.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(child.isAlive());
        assertTrue(childId > 0);
        assertFalse(parent == childId);
    }

    @Test
    public void concurrentThreadsHaveUniqueStableIdentities() throws Exception {
        final int participants = 8;
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<long[]>> futures = new ArrayList<>();
            for (int i = 0; i < participants; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        throw new AssertionError("workers did not start");
                    long first = ThreadIdentity.current();
                    Thread.currentThread().setName("identity-worker-" + first);
                    long second = ThreadIdentity.current();
                    return new long[] {first, second};
                }));
            }
            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            start.countDown();
            Set<Long> ids = new HashSet<>();
            for (Future<long[]> future : futures) {
                long[] values = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertTrue(values[0] > 0);
                assertEquals(values[0], values[1]);
                assertTrue(ids.add(values[0]));
            }
            assertEquals(participants, ids.size());
        } finally {
            start.countDown();
            shutdown(executor);
        }
    }

    @Test
    public void pooledWorkerKeepsIdentityWhenReusedAcrossTasks() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> first = executor.submit(ThreadIdentity::current);
            Future<Long> second = executor.submit(() -> {
                Thread.currentThread().setName("reused-worker");
                return ThreadIdentity.current();
            });
            assertEquals(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).longValue(),
                    second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).longValue());
        } finally {
            shutdown(executor);
        }
    }

    @Test
    public void finishedThreadIdentitiesAreNeverReused() throws Exception {
        final int count = 32;
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            FutureTask<Long> task = new FutureTask<>(ThreadIdentity::current);
            Thread thread = new Thread(task, "finished-" + i);
            thread.start();
            long id = task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(thread.isAlive());
            assertTrue(id > 0);
            assertTrue("identity was reused: " + id, ids.add(id));
        }
    }

    @Test
    public void boxedIdentitiesCompareByValueBeyondLongCache() {
        ThreadIdentity.Sequence sequence = new ThreadIdentity.Sequence(128);
        Long first = Long.valueOf(sequence.next());
        Long second = Long.valueOf(sequence.next());
        assertTrue(first > 128L);
        assertTrue(second > 128L);
        Long sameOwner = Long.valueOf(Long.parseLong(first.toString()));
        assertTrue(first.equals(sameOwner));
        assertFalse(first.equals(second));
        assertEquals(129L, first.longValue());
        assertEquals(130L, second.longValue());
    }

    @Test
    public void sequenceRejectsInvalidSeeds() {
        expectIllegalArgument(() -> new ThreadIdentity.Sequence(-1));
    }

    @Test
    public void sequenceExhaustionFailsWithoutWrapping() {
        ThreadIdentity.Sequence sequence = new ThreadIdentity.Sequence(Long.MAX_VALUE - 1);
        assertEquals(Long.MAX_VALUE, sequence.next());
        expectIllegalState(sequence::next);
        expectIllegalState(sequence::next);
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private static void expectIllegalArgument(Callable<?> action) {
        try {
            action.call();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        } catch (Exception unexpected) {
            fail("unexpected exception: " + unexpected);
        }
    }

    private static void expectIllegalState(Callable<?> action) {
        try {
            action.call();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        } catch (Exception unexpected) {
            fail("unexpected exception: " + unexpected);
        }
    }
}
