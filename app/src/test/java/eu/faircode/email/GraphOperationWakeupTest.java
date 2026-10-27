package eu.faircode.email;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GraphOperationWakeupTest {
    @Test
    public void classifiesAllGraphWorkerWriteOperations() {
        assertTrue(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.SEEN));
        assertTrue(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.FLAG));
        assertTrue(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.MOVE));
        assertTrue(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.DELETE));
        assertTrue(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.GRAPH_PURGE_TRASH));
        assertTrue(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.GRAPH_PERMANENT_DELETE));
        assertTrue(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.GRAPH_DELETE_PERMANENT));
        assertTrue(EntityOperation.isGraphApiMutation(EntityOperation.GRAPH_DELETE_PERMANENT));
        assertTrue(EntityOperation.isGraphApiRemoteWrite(EntityOperation.GRAPH_DELETE_PERMANENT));

        assertFalse(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.SYNC));
        assertFalse(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.PURGE));
        assertFalse(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.COPY));
    }

    @Test
    public void onlyAnIncreaseForAGraphAccountRequestsAWake() {
        assertTrue(GraphOperationWakeup.shouldWake(true, 0, 1));
        assertFalse(GraphOperationWakeup.shouldWake(false, 0, 1));
        assertFalse(GraphOperationWakeup.shouldWake(true, 1, 1));
        assertFalse(GraphOperationWakeup.shouldWake(true, 2, 1));
    }

    @Test
    public void queuedWriteWakesSleepingMonitorWithoutInterruptingIt() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch woken = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        Core.State monitor = new Core.State(null);
        monitor.runnable(new Runnable() {
            @Override
            public void run() {
                waiting.countDown();
                try {
                    monitor.acquire(TimeUnit.SECONDS.toMillis(30), false);
                    woken.countDown();
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }
        }, "test.graph-write-monitor");
        monitor.start();
        try {
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            assertTrue(GraphOperationWakeup.wake(monitor));
            assertTrue(woken.await(2, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
        } finally {
            monitor.stop();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void queuedWriteDoesNotReleaseRetryAfterWait() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Core.State monitor = new Core.State(null);
        monitor.runnable(new Runnable() {
            @Override
            public void run() {
                waiting.countDown();
                try {
                    monitor.acquireRateLimitWait(TimeUnit.SECONDS.toMillis(30));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }
        }, "test.graph-write-rate-limit");
        monitor.start();
        try {
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!monitor.isBackingOff() && System.nanoTime() < deadline)
                Thread.sleep(1);
            assertTrue("Monitor should be in Retry-After wait", monitor.isBackingOff());
            assertFalse(GraphOperationWakeup.wake(monitor));
            assertTrue(monitor.isBackingOff());
        } finally {
            monitor.stop();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        }
    }
}
