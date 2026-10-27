package eu.faircode.email;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FolderSyncWakeupTest {
    @Test
    public void dispatchDoesNotPerformTheRequestedFolderQueryOnTheCallingThread() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> queryThread = new AtomicReference<>();
        AtomicReference<Throwable> callbackError = new AtomicReference<>();
        AtomicBoolean woke = new AtomicBoolean();
        QueuedExecutor executor = new QueuedExecutor();

        FolderSyncWakeup.enqueue(executor, Collections.singletonList(17L),
                new FolderSyncWakeup.Listener() {
                    @Override
                    public boolean hasRequestedFolderSync(long accountId) {
                        queryThread.set(Thread.currentThread());
                        return true;
                    }

                    @Override
                    public void wake(long accountId) {
                        assertEquals(17L, accountId);
                        woke.set(true);
                    }

                    @Override
                    public void onError(long accountId, Throwable ex) {
                        callbackError.set(ex);
                    }
                });

        assertNull("The caller (onReload/onEval) must not enter the Room-backed query",
                queryThread.get());
        assertFalse(woke.get());
        executor.runOnWorker();

        assertNull(callbackError.get());
        assertNotSame(caller, queryThread.get());
        assertTrue(woke.get());
    }

    @Test
    public void explicitRefreshReleasesSleepingMonitorAndLeavesDurableRequestQueued() throws Exception {
        AtomicBoolean durableRequest = new AtomicBoolean(true);
        AtomicReference<Thread> queryThread = new AtomicReference<>();
        AtomicReference<Throwable> callbackError = new AtomicReference<>();
        CountDownLatch monitorWaiting = new CountDownLatch(1);
        CountDownLatch monitorWoken = new CountDownLatch(1);
        CountDownLatch monitorFinished = new CountDownLatch(1);
        QueuedExecutor executor = new QueuedExecutor();
        Core.State monitor = new Core.State(null);
        monitor.runnable(new Runnable() {
            @Override
            public void run() {
                monitorWaiting.countDown();
                try {
                    monitor.acquire(TimeUnit.SECONDS.toMillis(30), false);
                    monitorWoken.countDown();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    monitorFinished.countDown();
                }
            }
        }, "test.api-folder-monitor");
        monitor.start();

        try {
            assertTrue(monitorWaiting.await(2, TimeUnit.SECONDS));
            FolderSyncWakeup.enqueue(executor, Collections.singletonList(23L),
                    new FolderSyncWakeup.Listener() {
                        @Override
                        public boolean hasRequestedFolderSync(long accountId) {
                            queryThread.set(Thread.currentThread());
                            return durableRequest.get();
                        }

                        @Override
                        public void wake(long accountId) {
                            assertEquals(23L, accountId);
                            monitor.release();
                        }

                        @Override
                        public void onError(long accountId, Throwable ex) {
                            callbackError.set(ex);
                        }
                    });

            assertFalse("The monitor should still be asleep before its queued worker runs",
                    monitorWoken.await(100, TimeUnit.MILLISECONDS));
            assertTrue("The queue row is durable and remains present before wake", durableRequest.get());
            executor.runOnWorker();

            assertTrue("An explicit refresh must release the sleeping monitor",
                    monitorWoken.await(2, TimeUnit.SECONDS));
            assertTrue("Waking must not consume or delete the queued sync row", durableRequest.get());
            assertNotSame(Thread.currentThread(), queryThread.get());
            assertNull(callbackError.get());
        } finally {
            monitor.stop();
            assertTrue(monitorFinished.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void explicitRefreshDuringInFlightWorkIsConsumedAtTheNextMonitorWait() throws Exception {
        AtomicBoolean durableRequest = new AtomicBoolean(true);
        AtomicReference<Thread> queryThread = new AtomicReference<>();
        AtomicReference<Throwable> callbackError = new AtomicReference<>();
        CountDownLatch monitorInFlight = new CountDownLatch(1);
        CountDownLatch finishInFlight = new CountDownLatch(1);
        CountDownLatch monitorWoken = new CountDownLatch(1);
        CountDownLatch monitorFinished = new CountDownLatch(1);
        QueuedExecutor executor = new QueuedExecutor();
        Core.State monitor = new Core.State(null);
        monitor.runnable(new Runnable() {
            @Override
            public void run() {
                monitorInFlight.countDown();
                try {
                    finishInFlight.await(5, TimeUnit.SECONDS);
                    monitor.acquire(TimeUnit.SECONDS.toMillis(30), false);
                    monitorWoken.countDown();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    monitorFinished.countDown();
                }
            }
        }, "test.api-folder-monitor-in-flight");
        monitor.start();

        try {
            assertTrue(monitorInFlight.await(2, TimeUnit.SECONDS));
            FolderSyncWakeup.enqueue(executor, Collections.singletonList(29L),
                    new FolderSyncWakeup.Listener() {
                        @Override
                        public boolean hasRequestedFolderSync(long accountId) {
                            queryThread.set(Thread.currentThread());
                            return durableRequest.get();
                        }

                        @Override
                        public void wake(long accountId) {
                            assertEquals(29L, accountId);
                            monitor.release();
                        }

                        @Override
                        public void onError(long accountId, Throwable ex) {
                            callbackError.set(ex);
                        }
                    });
            executor.runOnWorker();

            assertFalse("Waking must not interrupt work already in flight",
                    monitorWoken.await(100, TimeUnit.MILLISECONDS));
            assertTrue("The queued sync request remains durable", durableRequest.get());
            finishInFlight.countDown();
            assertTrue("The queued wake permit must be consumed at the next wait",
                    monitorWoken.await(2, TimeUnit.SECONDS));
            assertTrue(durableRequest.get());
            assertNotSame(Thread.currentThread(), queryThread.get());
            assertNull(callbackError.get());
        } finally {
            finishInFlight.countDown();
            monitor.stop();
            assertTrue(monitorFinished.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void explicitRefreshDoesNotShortenGraphRateLimitWait() throws Exception {
        CountDownLatch monitorFinished = new CountDownLatch(1);
        AtomicReference<Throwable> waitError = new AtomicReference<>();
        Core.State monitor = new Core.State(null);
        monitor.runnable(new Runnable() {
            @Override
            public void run() {
                try {
                    monitor.acquireRateLimitWait(500L);
                } catch (Throwable ex) {
                    waitError.set(ex);
                } finally {
                    monitorFinished.countDown();
                }
            }
        }, "test.api-rate-limit-wait");
        monitor.start();

        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!monitor.isBackingOff() && System.nanoTime() < deadline)
                Thread.sleep(5L);
            assertTrue("The monitor must enter its rate-limit cooldown", monitor.isBackingOff());
            assertFalse("A manual folder request must not release a rate-limit wait",
                    monitor.releaseUnlessBackingOff());
            assertFalse("The monitor must honor the cooldown duration",
                    monitorFinished.await(100, TimeUnit.MILLISECONDS));
            assertTrue("The monitor should resume when the cooldown expires",
                    monitorFinished.await(2, TimeUnit.SECONDS));
            assertNull(waitError.get());
        } finally {
            monitor.stop();
            assertTrue(monitorFinished.await(2, TimeUnit.SECONDS));
        }
    }

    private static final class QueuedExecutor implements Executor {
        private Runnable task;

        @Override
        public synchronized void execute(Runnable command) {
            assertNull("Only one test task is expected", task);
            task = command;
        }

        void runOnWorker() throws InterruptedException {
            final Runnable queued;
            synchronized (this) {
                queued = task;
                task = null;
            }
            assertTrue("Expected an enqueued worker task", queued != null);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    queued.run();
                } catch (Throwable ex) {
                    failure.set(ex);
                }
            }, "test.folder-sync-wakeup");
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse("Wake worker did not finish", worker.isAlive());
            if (failure.get() != null)
                throw new AssertionError("Wake worker failed", failure.get());
        }
    }
}
