/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

package eu.faircode.email;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Sequential, resumable byte-range upload logic for Microsoft Graph attachment sessions. */
final class GraphAttachmentUpload {
    // Microsoft requires each PUT range to be no larger than 4 MB. Keep it strictly below 4 MB.
    static final int CHUNK_SIZE = 4 * 1024 * 1024 - 1;
    static final int CHUNK_TIMEOUT_SECONDS = GraphTimeouts.UPLOAD_CHUNK_RESPONSE_MILLIS / 1000;
    static final int MAX_RECOVERIES = 3;
    static final long MAX_OVERALL_TIMEOUT_MILLIS = GraphTimeouts.MAX_OVERALL_UPLOAD_FLOW_MILLIS;

    static final class Range {
        final long start;
        final long end;

        Range(long start, long end) {
            this.start = start;
            this.end = end;
        }

        long length() {
            return end - start + 1;
        }
    }

    static final class Session {
        final String uploadUrl;
        final List<String> nextExpectedRanges;

        Session(String uploadUrl, List<String> nextExpectedRanges) {
            this.uploadUrl = uploadUrl;
            this.nextExpectedRanges = (nextExpectedRanges == null
                    ? null : new ArrayList<>(nextExpectedRanges));
        }
    }

    static final class PutResult {
        final int status;
        final List<String> nextExpectedRanges;

        PutResult(int status, List<String> nextExpectedRanges) {
            this.status = status;
            this.nextExpectedRanges = (nextExpectedRanges == null
                    ? null : new ArrayList<>(nextExpectedRanges));
        }
    }

    static final class HttpFailure extends IOException {
        final int status;
        final String retryAfter;

        HttpFailure(int status, String retryAfter) {
            super("Microsoft Graph attachment upload HTTP " + status);
            this.status = status;
            this.retryAfter = retryAfter;
        }
    }

    interface Transport {
        Session createSession(String messageId, GraphAttachmentPlan.Attachment attachment) throws IOException;

        Session getUploadStatus(String opaqueUploadUrl) throws IOException;

        PutResult putRange(String opaqueUploadUrl, File file, Range range, long totalSize) throws IOException;
    }

    private GraphAttachmentUpload() {
    }

    static void upload(Transport transport, String messageId,
                       GraphAttachmentPlan.Attachment attachment) throws IOException {
        checkInterrupted();
        if (attachment.size < GraphAttachmentPlan.LARGE_ATTACHMENT_THRESHOLD ||
                attachment.size > GraphAttachmentPlan.MAX_ATTACHMENT_SIZE)
            throw new IllegalArgumentException("Invalid Microsoft Graph upload-session attachment size");
        if (!attachment.file.isFile() || !attachment.file.canRead() ||
                attachment.file.length() != attachment.size)
            throw new IOException("Microsoft Graph attachment file changed before upload");

        Session session = transport.createSession(messageId, attachment);
        validateSession(session);
        int recoveries = 0;
        long previousStart = -1;

        while (true) {
            checkInterrupted();
            Range range = nextRange(session.nextExpectedRanges, attachment.size);
            if (range.start <= previousStart)
                throw new IOException("Microsoft Graph upload session did not advance");
            previousStart = range.start;

            try {
                PutResult result = transport.putRange(session.uploadUrl, attachment.file,
                        range, attachment.size);
                if (result.status == 201)
                    return;
                if (result.status != 200) {
                    if (!isRetryable(result.status))
                        throw new HttpFailure(result.status, null);
                    if (++recoveries > MAX_RECOVERIES)
                        throw new HttpFailure(result.status, null);
                    session = recover(transport, session.uploadUrl, null);
                    previousStart = -1;
                    continue;
                }
                session = new Session(session.uploadUrl, result.nextExpectedRanges);
                Range next = nextRange(session.nextExpectedRanges, attachment.size);
                if (next.start <= range.start)
                    throw new IOException("Microsoft Graph upload session did not acknowledge the byte range");
                recoveries = 0;
                previousStart = -1;
            } catch (HttpFailure ex) {
                if (Thread.currentThread().isInterrupted())
                    throw ex;
                if (!isRetryable(ex.status) || ++recoveries > MAX_RECOVERIES)
                    throw ex;
                session = recover(transport, session.uploadUrl, ex.retryAfter);
                previousStart = -1;
            } catch (IOException ex) {
                if (Thread.currentThread().isInterrupted())
                    throw ex;
                if (++recoveries > MAX_RECOVERIES)
                    throw ex;
                // The PUT may have reached Graph. Query the session before retrying any bytes.
                session = recover(transport, session.uploadUrl, null);
                previousStart = -1;
            }
        }
    }

    static Range nextRange(List<String> expectedRanges, long totalSize) throws IOException {
        if (expectedRanges == null || expectedRanges.isEmpty() || totalSize <= 0)
            throw new IOException("Microsoft Graph returned no expected upload range");
        String value = expectedRanges.get(0);
        if (value == null)
            throw new IOException("Microsoft Graph returned an invalid upload range");
        value = value.trim();
        int dash = value.indexOf('-');
        String first = (dash < 0 ? value : value.substring(0, dash)).trim();
        try {
            long start = Long.parseLong(first);
            if (start < 0 || start >= totalSize)
                throw new IOException("Microsoft Graph returned an out-of-range upload offset");
            long expectedEnd = totalSize - 1;
            if (dash >= 0 && dash + 1 < value.length()) {
                long advertisedEnd = Long.parseLong(value.substring(dash + 1).trim());
                if (advertisedEnd < start)
                    throw new IOException("Microsoft Graph returned an invalid upload range");
                expectedEnd = Math.min(expectedEnd, advertisedEnd);
            }
            long end = Math.min(expectedEnd, start + CHUNK_SIZE - 1L);
            return new Range(start, end);
        } catch (NumberFormatException ex) {
            throw new IOException("Microsoft Graph returned an invalid upload offset", ex);
        }
    }

    static String contentRange(Range range, long totalSize) {
        return "bytes " + range.start + "-" + range.end + "/" + totalSize;
    }

    static long overallTimeoutMillis() {
        return MAX_OVERALL_TIMEOUT_MILLIS;
    }

    static long retryAfterMillis(String value, long nowMillis) {
        if (value == null)
            return 0;
        String retry = value.trim();
        try {
            long seconds = Long.parseLong(retry);
            if (seconds <= 0)
                return 0;
            return (seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L);
        } catch (NumberFormatException ex) {
            // Retry-After can also be an HTTP-date.
        }
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        try {
            Date date = format.parse(retry);
            return (date == null ? 0 : Math.max(0, date.getTime() - nowMillis));
        } catch (ParseException ex) {
            return 0;
        }
    }

    static boolean isRetryable(int status) {
        return (status == 429 || status >= 500 && status <= 599);
    }

    private static Session recover(Transport transport, String uploadUrl, String retryAfter)
            throws IOException {
        checkInterrupted();
        long delay = retryAfterMillis(retryAfter, System.currentTimeMillis());
        if (delay > 0)
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to resume Microsoft Graph attachment upload", ex);
            }
        checkInterrupted();
        Session recovered = transport.getUploadStatus(uploadUrl);
        validateSession(recovered);
        return recovered;
    }

    private static void validateSession(Session session) throws IOException {
        if (session == null || session.uploadUrl == null ||
                !session.uploadUrl.regionMatches(true, 0, "https://", 0, 8))
            throw new IOException("Microsoft Graph returned an invalid upload session");
        if (session.nextExpectedRanges == null || session.nextExpectedRanges.isEmpty())
            throw new IOException("Microsoft Graph returned no expected upload range");
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted())
            throw new IOException("Microsoft Graph attachment upload interrupted");
    }
}
