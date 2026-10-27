package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Host recipes plus exact-source gates, not live Android/SQLite/provider tests. */
public class TransactionReferenceResultTest {
    private interface Call { void run() throws Exception; }

    private static Throwable failure(Call call) {
        try {
            call.run();
            return null;
        } catch (Throwable ex) {
            return ex;
        }
    }

    private static void raise(Throwable ex) {
        if (ex instanceof Error)
            throw (Error) ex;
        if (ex != null)
            throw (RuntimeException) ex;
    }

    private static final class Row {
        final String id;
        final Long account;
        boolean message = true, folder = true, owner = true, inbox = true, uid = true;
        int protocol; // 0: IMAP, 1: POP, 2: other
        Boolean raw = false;
        String hash;

        Row(String id, Long account) {
            this.id = id;
            this.account = account;
            this.hash = id;
        }
    }

    /** Same body for both delimiter recipes; source gates separately pin real body bytes. */
    private static Long forwardBody(TransactionCallableRoomModel room, Row[] rows,
                                    boolean threads, List<String> result, Throwable bodyFailure) {
        Long selectedAccountId = null;
        List<String> hashes = new ArrayList<>();
        for (Row row : rows) {
            room.trace.add("message:" + row.id);
            if (!row.message)
                continue;
            if (rows.length == 1)
                room.trace.add("subject:" + row.id);
            room.trace.add("folder:" + row.id);
            if (!row.folder)
                continue;
            room.trace.add("account:" + row.id);
            if (!row.owner)
                continue;
            if (selectedAccountId == null)
                selectedAccountId = row.account;
            else if (!selectedAccountId.equals(row.account))
                selectedAccountId = -1L;
            if (row.protocol == 0 && !row.uid)
                continue;
            if (row.protocol == 1 && !row.inbox)
                continue;
            room.trace.add("thread:" + row.id);
            if (threads) {
                if (hashes.contains(row.hash))
                    continue;
                hashes.add(row.hash);
            }
            result.add(row.id);
            room.trace.add("result:" + row.id);
            if (row.raw == null || !row.raw)
                room.write("raw:" + row.id);
        }
        raise(bodyFailure);
        return selectedAccountId;
    }

    private static Long forward(boolean modern, TransactionCallableRoomModel room, Row[] rows,
                                boolean threads, List<String> result, Throwable bodyFailure)
            throws Exception {
        Long aid;
        if (modern)
            aid = TransactionScope.call(room::run, room::end,
                    () -> forwardBody(room, rows, threads, result, bodyFailure));
        else {
            aid = null;
            try {
                room.begin();
                aid = forwardBody(room, rows, threads, result, bodyFailure);
                room.commit();
            } finally {
                room.end();
            }
        }
        room.trace.add("eval:raw");
        room.trace.add("args:account:" + (aid == null ? -1L : aid));
        room.trace.add("toLongArray");
        return aid;
    }

    private static final class Folder {
        String name = "sent";
    }

    private static void sendBody(TransactionCallableRoomModel room, Long sid, Folder sent,
                                 boolean orphan, boolean queueExists, Throwable bodyFailure) {
        room.trace.add("query:orphan:" + sid);
        if (orphan)
            if (queueExists)
                room.write("exists:" + sid);
            else if (sent != null)
                room.trace.add("contact:" + sent.name);
        raise(bodyFailure);
    }

    private static void send(boolean modern, TransactionCallableRoomModel room, Long sid,
                             Folder sent, boolean orphan, boolean queueExists, Throwable bodyFailure) {
        if (sid != null) {
            if (modern) {
                final Long sentMessageId = sid;
                final Folder sentFolder = sent;
                TransactionScope.run(callback -> room.run(() -> {
                    callback.run();
                    return null;
                }), room::end, () -> sendBody(room, sentMessageId, sentFolder,
                        orphan, queueExists, bodyFailure));
            } else
                try {
                    room.begin();
                    sendBody(room, sid, sent, orphan, queueExists, bodyFailure);
                    room.commit();
                } finally {
                    room.end();
                }
            room.trace.add("calendar:" + sid);
        }
        room.trace.add("eval:sent");
    }

    private static Long assertForward(Row[] rows, boolean threads, List<String> result)
            throws Exception {
        TransactionCallableRoomModel old = new TransactionCallableRoomModel();
        TransactionCallableRoomModel modern = new TransactionCallableRoomModel();
        List<String> oldResult = new ArrayList<>();
        Long before = forward(false, old, rows, threads, oldResult, null);
        Long after = forward(true, modern, rows, threads, result, null);
        assertEquals(before, after);
        assertEquals(oldResult, result);
        assertEquals(old.trace, modern.trace);
        assertEquals(old.committed, modern.committed);
        assertTrue(modern.trace.indexOf("eval:raw") > modern.trace.indexOf("end"));
        return after;
    }

    @Test
    public void nullableAccountCommitsAndBareNonCachedAccountReferenceIsReturned() throws Exception {
        assertNull(assertForward(new Row[0], true, new ArrayList<>()));
        Long account = Long.valueOf(1000);
        assertSame(account, assertForward(new Row[]{new Row("a", account), new Row("b", account)},
                true, new ArrayList<>()));
    }

    @Test
    public void mixedAccountAggregationStillPrecedesImapAndPopFilters() throws Exception {
        Row imap = new Row("imap", 1000L);
        imap.uid = false;
        Row pop = new Row("pop", 1001L);
        pop.protocol = 1;
        pop.inbox = false;
        List<String> result = new ArrayList<>();
        assertEquals(Long.valueOf(-1L), assertForward(new Row[]{imap, pop}, true, result));
        assertTrue(result.isEmpty());
    }

    @Test
    public void protocolFiltersDoNotRequireUidForPopOrOtherProtocols() throws Exception {
        Row pop = new Row("pop", 1000L);
        pop.protocol = 1;
        pop.uid = false;
        Row other = new Row("other", 1000L);
        other.protocol = 2;
        other.uid = false;
        other.inbox = false;
        List<String> result = new ArrayList<>();
        assertForward(new Row[]{pop, other}, true, result);
        assertEquals(Arrays.asList("pop", "other"), result);
    }

    @Test
    public void hashDedupPrecedesResultEvenForAlreadyRawThreads() throws Exception {
        Row first = new Row("first", 1L);
        first.raw = true;
        Row duplicate = new Row("duplicate", 1L);
        duplicate.hash = first.hash;
        Row missingRaw = new Row("null-raw", 1L);
        missingRaw.raw = null;
        List<String> result = new ArrayList<>();
        assertForward(new Row[]{first, duplicate, missingRaw}, true, result);
        assertEquals(Arrays.asList("first", "null-raw"), result);
        result.clear();
        assertForward(new Row[]{first, duplicate, missingRaw}, false, result);
        assertEquals(Arrays.asList("first", "duplicate", "null-raw"), result);
    }

    @Test
    public void missingMessageFolderAndAccountSkipAtOriginalPoints() throws Exception {
        Row message = new Row("missing-message", 1L);
        message.message = false;
        Row folder = new Row("missing-folder", 2L);
        folder.folder = false;
        Row account = new Row("missing-account", 3L);
        account.owner = false;
        assertNull(assertForward(new Row[]{message, folder, account}, true, new ArrayList<>()));
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        forward(true, room, new Row[]{folder}, true, new ArrayList<>(), null);
        assertTrue(room.trace.indexOf("subject:missing-folder") < room.trace.indexOf("folder:missing-folder"));
    }

    @Test
    public void resultAdditionPrecedesRawQueueAndAllPostWorkFollowsEnd() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        forward(true, room, new Row[]{new Row("a", 1L)}, true, new ArrayList<>(), null);
        assertTrue(room.trace.indexOf("result:a") < room.trace.indexOf("write:raw:a"));
        assertEquals(Arrays.asList("raw:a"), room.committed);
        assertEquals(Arrays.asList("end", "eval:raw", "args:account:1", "toLongArray"),
                room.trace.subList(room.trace.size() - 4, room.trace.size()));
    }

    @Test
    public void sentOrphanExistsContactAndNullSidBranchesAreIdentical() {
        for (Long sid : new Long[]{null, 1000L})
            for (boolean orphan : new boolean[]{false, true})
                for (boolean queue : new boolean[]{false, true})
                    for (Folder folder : new Folder[]{null, new Folder()}) {
                        TransactionCallableRoomModel old = new TransactionCallableRoomModel();
                        TransactionCallableRoomModel modern = new TransactionCallableRoomModel();
                        send(false, old, sid, folder, orphan, queue, null);
                        send(true, modern, sid, folder, orphan, queue, null);
                        assertEquals(old.trace, modern.trace);
                        assertEquals(old.committed, modern.committed);
                        if (sid != null)
                            assertTrue(modern.trace.indexOf("calendar:1000") > modern.trace.indexOf("end"));
                    }
    }

    @Test
    public void finalSnapshotsRetainObjectIdentityAndSeeMutableFolderFields() {
        Long sid = Long.valueOf(1000);
        Folder folder = new Folder();
        final Long sentMessageId = sid;
        final Folder sentFolder = folder;
        folder.name = "changed";
        assertSame(sid, sentMessageId);
        assertSame(folder, sentFolder);
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        send(true, room, sentMessageId, sentFolder, true, false, null);
        assertTrue(room.trace.contains("contact:changed"));
    }

    @Test
    public void beginBodyMarkerAndEndFailuresKeepIdentityAndSkipActualPostWork() throws Exception {
        for (boolean raw : new boolean[]{false, true})
            for (int stage = 0; stage < 4; stage++)
                for (boolean mask : new boolean[]{false, true}) {
                    RuntimeException original = new IllegalArgumentException("stage:" + stage);
                    Error end = new AssertionError("distinct-end");
                    TransactionCallableRoomModel old = new TransactionCallableRoomModel();
                    TransactionCallableRoomModel modern = new TransactionCallableRoomModel();
                    for (TransactionCallableRoomModel room : new TransactionCallableRoomModel[]{old, modern}) {
                        room.beginFailure = stage == 0 ? original : null;
                        room.commitFailure = stage == 2 ? original : null;
                        room.endFailure = mask ? end : stage == 3 ? original : null;
                    }
                    Throwable body = stage == 1 ? original : null;
                    Call before = () -> invoke(raw, false, old, body);
                    Call after = () -> invoke(raw, true, modern, body);
                    assertSame(mask ? end : original, failure(before));
                    assertSame(mask ? end : original, failure(after));
                    assertEquals(old.trace, modern.trace);
                    assertEquals(old.committed, modern.committed);
                    assertFalse(modern.trace.contains("eval:raw"));
                    assertFalse(modern.trace.contains("eval:sent"));
                    assertFalse(modern.trace.contains("calendar:1000"));
                }
    }

    private static void invoke(boolean raw, boolean modern, TransactionCallableRoomModel room,
                               Throwable body) throws Exception {
        if (raw)
            forward(modern, room, new Row[]{new Row("a", 1000L)}, true, new ArrayList<>(), body);
        else
            send(modern, room, 1000L, new Folder(), true, true, body);
    }
}
