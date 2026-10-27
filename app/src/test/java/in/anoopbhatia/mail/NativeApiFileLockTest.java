package in.anoopbhatia.mail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class NativeApiFileLockTest {
    @Test
    public void cleanupWaitsForEveryNativeStagingFileAndAvailabilityPublication() throws Exception {
        for (String prefix : new String[]{"native-attachment-", "native-body-", "native-raw-"}) {
            File directory = Files.createTempDirectory("native-cleanup-test").toFile();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            CountDownLatch attempted = new CountDownLatch(1);
            AtomicBoolean available = new AtomicBoolean();
            Future<?> cleanup = null;
            File destination = new File(directory, "123");
            try {
                try (NativeApiFileLock.Guard ignored = NativeApiFileLock.fetch()) {
                    File temporary = File.createTempFile(prefix, ".tmp", directory);
                    Files.write(temporary.toPath(), "complete".getBytes(StandardCharsets.UTF_8));
                    // Simulate the manual cleanup's nonnumeric-file and availability checks.
                    cleanup = executor.submit(() -> {
                        attempted.countDown();
                        try (NativeApiFileLock.Guard guard = NativeApiFileLock.cleanup()) {
                            File[] files = directory.listFiles();
                            assertNotNull(files);
                            for (File file : files)
                                if (!file.getName().equals("123") || !available.get())
                                    assertTrue(file.delete());
                        }
                    });
                    assertTrue(attempted.await(5, TimeUnit.SECONDS));
                    assertBlocked(cleanup);
                    assertTrue(temporary.isFile());
                    NativeApiContentOperations.installAtomically(temporary, destination, false);
                    // Cleanup must also wait in the gap after rename but before DB availability.
                    assertBlocked(cleanup);
                    available.set(true);
                }
                cleanup.get(5, TimeUnit.SECONDS);
                assertEquals("complete", new String(Files.readAllBytes(destination.toPath()),
                        StandardCharsets.UTF_8));
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                File[] files = directory.listFiles();
                if (files != null)
                    for (File file : files)
                        assertTrue(file.delete());
                assertTrue(directory.delete());
            }
        }
    }

    @Test
    public void cleanupExcludesNewFetchesButConcurrentFetchesAreAllowed() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (NativeApiFileLock.Guard ignored = NativeApiFileLock.fetch()) {
                executor.submit(() -> {
                    try (NativeApiFileLock.Guard guard = NativeApiFileLock.fetch()) {
                        return true;
                    }
                }).get(5, TimeUnit.SECONDS);
            }
            Future<Boolean> fetch;
            CountDownLatch attempted = new CountDownLatch(1);
            try (NativeApiFileLock.Guard ignored = NativeApiFileLock.cleanup()) {
                fetch = executor.submit(() -> {
                    attempted.countDown();
                    try (NativeApiFileLock.Guard guard = NativeApiFileLock.fetch()) {
                        return true;
                    }
                });
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                assertBlocked(fetch);
            }
            assertTrue(fetch.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void failedRawFetchReleasesProtectionAndRemovesPartialFile() throws Exception {
        File directory = Files.createTempDirectory("native-raw-lock-test").toFile();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try {
                NativeApiContentOperations.ensureRawFile(false, new File(directory, "123"), temporary -> {
                    assertTrue(temporary.isFile());
                    throw new IOException("interrupted download");
                });
                fail("Expected download failure");
            } catch (IOException expected) {
                assertEquals("interrupted download", expected.getMessage());
            }
            executor.submit(() -> {
                try (NativeApiFileLock.Guard ignored = NativeApiFileLock.cleanup()) {
                    return true;
                }
            }).get(5, TimeUnit.SECONDS);
            assertEquals(0, directory.listFiles().length);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(directory.delete());
        }
    }

    private static void assertBlocked(Future<?> future) throws Exception {
        try {
            future.get(100, TimeUnit.MILLISECONDS);
            fail("Cleanup and native file publication must not overlap");
        } catch (TimeoutException expected) {
            // Still blocked by the lifecycle guard.
        }
    }
}
