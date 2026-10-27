package eu.faircode.email;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZohoUploadTimeoutsTest {
    @Test
    public void sizeBudgetAllowsMoreThanOneMibAndUsesDocumentedUplinkFloor() {
        long oneMib = 1024L * 1024L;
        assertEquals(106_000L, ZohoUploadTimeouts.budgetMillis(oneMib));
        assertTrue(ZohoUploadTimeouts.budgetMillis(oneMib) > 30_000L);
        assertEquals(570_000L, ZohoUploadTimeouts.budgetMillis(30L * oneMib));
        assertEquals(64L * 1024L, ZohoUploadTimeouts.MIN_UPLINK_BYTES_PER_SECOND);
        assertEquals(60_000L, ZohoUploadTimeouts.READ_TIMEOUT_MILLIS);
    }

    @Test
    public void overallBudgetHasFirmMaximumEvenForHugeInput() {
        assertEquals(ZohoUploadTimeouts.MAX_OVERALL_UPLOAD_MILLIS,
                ZohoUploadTimeouts.budgetMillis(Long.MAX_VALUE));
        assertEquals(ZohoUploadTimeouts.MAX_OVERALL_UPLOAD_MILLIS,
                ZohoUploadTimeouts.budgetMillis(60L * 1024L * 1024L));
    }

    @Test
    public void noProgressTimeoutAbortsAndInterruptsTheBlockedUpload() throws Exception {
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch stopped = new CountDownLatch(1);
        List<String> stages = Collections.synchronizedList(new ArrayList<>());

        try {
            ZohoUploadTimeouts.execute(progress -> {
                progress.stage("waiting_response");
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(5));
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    stopped.countDown();
                    throw new IOException("cancelled test upload", ex);
                }
                return null;
            }, () -> aborted.set(true), TimeUnit.SECONDS.toMillis(2), 100L,
                    (stage, elapsed, bytes) -> stages.add(stage));
            fail("A stalled upload must time out");
        } catch (SocketTimeoutException expected) {
            assertEquals("Zoho attachment upload made no progress", expected.getMessage());
        }

        assertTrue(aborted.get());
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.get());
        assertTrue(stages.contains("timeout_no_progress"));
    }

    @Test
    public void overallDeadlineCancelsAnUploadThatContinuesMakingProgress() throws Exception {
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicLong bytes = new AtomicLong();
        List<String> stages = Collections.synchronizedList(new ArrayList<>());

        try {
            ZohoUploadTimeouts.execute(progress -> {
                progress.stage("writing");
                while (true) {
                    progress.bytesSent(bytes.incrementAndGet());
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException ex) {
                        interrupted.set(true);
                        stopped.countDown();
                        throw new IOException("cancelled test upload", ex);
                    }
                }
            }, () -> aborted.set(true), 150L, TimeUnit.SECONDS.toMillis(2),
                    (stage, elapsed, sent) -> stages.add(stage));
            fail("The absolute upload deadline must stop a progressing upload");
        } catch (SocketTimeoutException expected) {
            assertEquals("Zoho attachment upload exceeded its overall deadline", expected.getMessage());
        }

        assertTrue(aborted.get());
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.get());
        assertTrue(stages.contains("timeout_overall"));
    }
}
