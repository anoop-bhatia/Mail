package eu.faircode.email;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GraphAttachmentUploadTest {
    @Test
    public void createsSequentialRangesStrictlyBelowFourMegabytes() throws Exception {
        long total = (long) GraphAttachmentUpload.CHUNK_SIZE + 23;
        GraphAttachmentUpload.Range first = GraphAttachmentUpload.nextRange(
                Arrays.asList("0-"), total);
        GraphAttachmentUpload.Range second = GraphAttachmentUpload.nextRange(
                Arrays.asList(Long.toString(first.end + 1) + "-"), total);

        assertEquals(0, first.start);
        assertEquals(GraphAttachmentUpload.CHUNK_SIZE, first.length());
        assertTrue(first.length() < 4L * 1024L * 1024L);
        assertEquals("bytes 0-" + (GraphAttachmentUpload.CHUNK_SIZE - 1) + "/" + total,
                GraphAttachmentUpload.contentRange(first, total));
        assertEquals(GraphAttachmentUpload.CHUNK_SIZE, second.start);
        assertEquals(23, second.length());
    }

    @Test
    public void usesTheServerExpectedRangeToResumeAfterInterruptedPut() throws Exception {
        File file = sizedFile((long) GraphAttachmentUpload.CHUNK_SIZE + 17);
        try {
            GraphAttachmentPlan.Attachment attachment = attachment(file);
            RecordingTransport transport = new RecordingTransport();
            transport.failFirstPut = true;
            transport.resumeAt = GraphAttachmentUpload.CHUNK_SIZE;

            GraphAttachmentUpload.upload(transport, "draft-id", attachment);

            assertEquals(Arrays.asList(0L, (long) GraphAttachmentUpload.CHUNK_SIZE),
                    transport.putStarts);
            assertEquals(1, transport.statusQueries);
            assertEquals(201, transport.finalStatus);
        } finally {
            file.delete();
        }
    }

    @Test
    public void doesNotRetryNonRetryableHttpErrorsOrGuessAtTheNextByte() throws Exception {
        File file = sizedFile(GraphAttachmentPlan.LARGE_ATTACHMENT_THRESHOLD);
        try {
            RecordingTransport transport = new RecordingTransport();
            transport.putFailure = new GraphAttachmentUpload.HttpFailure(400, null);
            try {
                GraphAttachmentUpload.upload(transport, "draft-id", attachment(file));
                fail("Expected explicit Graph HTTP failure");
            } catch (GraphAttachmentUpload.HttpFailure ex) {
                assertEquals(400, ex.status);
                assertEquals(0, transport.statusQueries);
                assertEquals(1, transport.putStarts.size());
            }
        } finally {
            file.delete();
        }
    }

    @Test
    public void cancellationDuringPutStopsBeforeResumeQuery() throws Exception {
        File file = sizedFile(GraphAttachmentPlan.LARGE_ATTACHMENT_THRESHOLD);
        try {
            RecordingTransport transport = new RecordingTransport();
            transport.interruptFirstPut = true;
            try {
                GraphAttachmentUpload.upload(transport, "draft-id", attachment(file));
                fail("Expected interrupted upload");
            } catch (IOException expected) {
                assertEquals(1, transport.putStarts.size());
                assertEquals(0, transport.statusQueries);
            } finally {
                Thread.interrupted();
            }
        } finally {
            file.delete();
        }
    }

    @Test
    public void honorsRetryAfterAndKeepsTheOverallAttemptFinite() throws Exception {
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        long now = dateFormat.parse("Thu, 01 Jan 2026 00:00:00 GMT").getTime();
        assertEquals(4_000L, GraphAttachmentUpload.retryAfterMillis("4", now));
        assertEquals(5_000L, GraphAttachmentUpload.retryAfterMillis(
                "Thu, 01 Jan 2026 00:00:05 GMT", now));
        assertEquals(0, GraphAttachmentUpload.retryAfterMillis("not-a-date", now));
        assertEquals(300_000L, GraphAttachmentUpload.overallTimeoutMillis());
        assertEquals(GraphTimeouts.MAX_OVERALL_UPLOAD_FLOW_MILLIS,
                GraphAttachmentUpload.MAX_OVERALL_TIMEOUT_MILLIS);
        GraphAttachmentPlan.Attachment fiveMegabyte = new GraphAttachmentPlan.Attachment(
                new File("unused"), 5L * 1024L * 1024L, "five.bin",
                "application/octet-stream", null, false);
        assertEquals(300_000L, GraphAttachmentUpload.overallTimeoutMillis());
        assertEquals(90, GraphAttachmentUpload.CHUNK_TIMEOUT_SECONDS);
    }

    private static GraphAttachmentPlan.Attachment attachment(File file) {
        return new GraphAttachmentPlan.Attachment(file, file.length(), "large.bin",
                "application/octet-stream", null, false);
    }

    private static File sizedFile(long size) throws Exception {
        File file = File.createTempFile("graph-upload-test", ".bin");
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(size);
        }
        return file;
    }

    private static final class RecordingTransport implements GraphAttachmentUpload.Transport {
        final List<Long> putStarts = new ArrayList<>();
        boolean failFirstPut;
        boolean interruptFirstPut;
        long resumeAt;
        int statusQueries;
        int finalStatus;
        GraphAttachmentUpload.HttpFailure putFailure;

        @Override
        public GraphAttachmentUpload.Session createSession(String messageId,
                GraphAttachmentPlan.Attachment attachment) {
            return new GraphAttachmentUpload.Session("https://upload.example.test/opaque",
                    Arrays.asList("0-"));
        }

        @Override
        public GraphAttachmentUpload.Session getUploadStatus(String opaqueUploadUrl) {
            statusQueries++;
            return new GraphAttachmentUpload.Session(opaqueUploadUrl,
                    Arrays.asList(resumeAt + "-"));
        }

        @Override
        public GraphAttachmentUpload.PutResult putRange(String opaqueUploadUrl, File file,
                GraphAttachmentUpload.Range range, long totalSize) throws IOException {
            putStarts.add(range.start);
            if (putFailure != null)
                throw putFailure;
            if (interruptFirstPut && putStarts.size() == 1) {
                Thread.currentThread().interrupt();
                throw new IOException("simulated interrupted write");
            }
            if (failFirstPut && putStarts.size() == 1)
                throw new IOException("simulated connection interruption");
            if (range.end == totalSize - 1) {
                finalStatus = 201;
                return new GraphAttachmentUpload.PutResult(201, null);
            }
            long next = range.end + 1;
            return new GraphAttachmentUpload.PutResult(200, Arrays.asList(next + "-"));
        }
    }
}
