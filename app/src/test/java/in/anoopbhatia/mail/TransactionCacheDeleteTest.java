package in.anoopbhatia.mail;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Host recipes; the separate exact-source gate pins all production provider branches. */
public class TransactionCacheDeleteTest {
    private interface Action { void run(); }
    private static Throwable capture(Action action) {
        try { action.run(); return null; } catch (Throwable failure) { return failure; }
    }
    private static void raise(Throwable failure) {
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new AssertionError(failure);
    }
    private static final class CachedMessage {
        String preview = "old";
        boolean content;
        String file;
    }
    private static void cacheWrites(TransactionCallableRoomModel room, String preview, Throwable failure) {
        room.write("content:" + preview);
        room.write("headers");
        room.write("size");
        raise(failure);
    }
    private static void cache(boolean modern, TransactionCallableRoomModel room,
                              CachedMessage message, String preview, Throwable failure) {
        message.file = "installed-body"; // Same pre-begin file effect, outside the DB callback.
        if (modern) {
            final String cachedPreview = preview;
            room.run(() -> { cacheWrites(room, cachedPreview, failure); return null; });
        } else {
            room.begin();
            try {
                cacheWrites(room, preview, failure);
                room.commit();
            } finally { room.end(); }
        }
        message.content = true;
        message.preview = preview;
        room.trace.add("publish-message");
    }
    private enum Item { MISSING, HINTED_MISSING, STALE, GMAIL, GMAIL_REJECT,
        GRAPH, ZOHO, ZOHO_REJECT, LEGACY }
    private static final class DeleteState {
        final List<String> gmail = new ArrayList<>();
        final List<String> refresh = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        int skipped;
    }
    private static Boolean deleteBody(TransactionCallableRoomModel room, DeleteState state,
                                      Item[] items, Throwable failure) {
        boolean legacyWake = false;
        if (items != null)
            for (Item item : items) {
                room.trace.add("lookup:" + item);
                switch (item) {
                    case MISSING: state.removed.add("missing"); continue;
                    case HINTED_MISSING:
                    case STALE: state.refresh.add("stale"); state.skipped++; continue;
                    case GMAIL:
                        room.write("gmail-queue");
                        room.write("gmail-ui-busy");
                        state.gmail.add("gmail");
                        break;
                    case GMAIL_REJECT: state.skipped++; break;
                    case ZOHO_REJECT: state.skipped++; continue;
                    case GRAPH:
                    case ZOHO:
                    case LEGACY:
                        room.write("queue:" + item);
                        state.removed.add(item.name());
                        legacyWake = true;
                        continue;
                    default: throw new AssertionError(item);
                }
            }
        raise(failure);
        return legacyWake ? Boolean.TRUE : Boolean.FALSE;
    }
    private static boolean delete(boolean modern, TransactionCallableRoomModel room,
                                  DeleteState state, Item[] items, Throwable failure) {
        boolean wakeLegacy;
        if (modern) {
            wakeLegacy = TransactionScope.call(room::run, room::end,
                    () -> deleteBody(room, state, items, failure));
        } else {
            try {
                room.begin();
                wakeLegacy = deleteBody(room, state, items, failure);
                room.commit();
            } finally { room.end(); }
        }
        for (String account : state.gmail) room.trace.add("dispatch:" + account);
        for (String account : state.refresh) room.trace.add("reload:" + account);
        if (wakeLegacy) room.trace.add("eval:legacy");
        room.trace.add("return-result");
        return wakeLegacy;
    }
    @Test public void cacheSuccessKeepsThreeWritesAndPublishesOriginalPreviewAfterEnd() {
        for (boolean modern : new boolean[]{false, true})
            for (String preview : new String[]{null, "normalized-fallback"}) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                CachedMessage message = new CachedMessage();
                cache(modern, room, message, preview, null);
                assertEquals(Arrays.asList("begin", "write:content:" + preview, "write:headers",
                        "write:size", "commit", "end", "publish-message"), room.trace);
                assertEquals(Arrays.asList("content:" + preview, "headers", "size"), room.committed);
                assertEquals("installed-body", message.file);
                assertSame(preview, message.preview);
                assertTrue(message.content);
            }
    }
    @Test public void cacheBodyFailureRollsBackWritesButNotFileAndSkipsPublication() {
        for (Throwable failure : new Throwable[]{new IllegalStateException("body"), new AssertionError("body")})
            for (boolean modern : new boolean[]{false, true}) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                CachedMessage message = new CachedMessage();
                assertSame(failure, capture(() -> cache(modern, room, message, "new", failure)));
                assertEquals("installed-body", message.file);
                assertEquals("old", message.preview);
                assertFalse(message.content);
                assertTrue(room.committed.isEmpty());
                assertEquals(Arrays.asList("begin", "write:content:new", "write:headers", "write:size", "end"), room.trace);
            }
    }
    @Test public void cacheBeginMarkerAndEndFailuresRetainNoBeginCleanupAndFilePersistence() {
        for (boolean modern : new boolean[]{false, true})
            for (int stage = 0; stage < 3; stage++) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                Throwable failure = stage == 2 ? new AssertionError("end") : new IllegalStateException("failure");
                if (stage == 0) room.beginFailure = (RuntimeException) failure;
                if (stage == 1) room.commitFailure = (RuntimeException) failure;
                if (stage == 2) room.endFailure = failure;
                CachedMessage message = new CachedMessage();
                assertSame(failure, capture(() -> cache(modern, room, message, "new", null)));
                assertEquals("installed-body", message.file);
                assertEquals("old", message.preview);
                assertFalse(message.content);
                assertEquals(stage == 0 ? Collections.singletonList("begin") :
                        Arrays.asList("begin", "write:content:new", "write:headers", "write:size", "commit", "end"), room.trace);
                assertEquals(stage == 2 ? Arrays.asList("content:new", "headers", "size") : Collections.emptyList(), room.committed);
            }
    }
    @Test public void deleteMixedProviderSuccessKeepsOneTransactionAndPostEndDispatchOrder() {
        Item[] items = {Item.MISSING, Item.STALE, Item.GMAIL, Item.GMAIL_REJECT,
                Item.GRAPH, Item.ZOHO, Item.ZOHO_REJECT, Item.LEGACY};
        for (boolean modern : new boolean[]{false, true}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            DeleteState state = new DeleteState();
            assertTrue(delete(modern, room, state, items, null));
            assertEquals(Arrays.asList("gmail-queue", "gmail-ui-busy", "queue:GRAPH", "queue:ZOHO", "queue:LEGACY"), room.committed);
            assertEquals(Arrays.asList("missing", "GRAPH", "ZOHO", "LEGACY"), state.removed);
            assertEquals(3, state.skipped);
            assertEquals(1, Collections.frequency(room.trace, "begin"));
            assertEquals(1, Collections.frequency(room.trace, "end"));
            assertEquals(Arrays.asList("end", "dispatch:gmail", "reload:stale", "eval:legacy", "return-result"),
                    room.trace.subList(room.trace.indexOf("end"), room.trace.size()));
        }
    }
    @Test public void nullEmptyMissingAndStaleOnlyResultsNeverInventLegacyWake() {
        for (boolean modern : new boolean[]{false, true})
            for (Item[] items : new Item[][]{null, {}, {Item.MISSING}, {Item.HINTED_MISSING}, {Item.STALE}, {Item.GMAIL_REJECT}, {Item.ZOHO_REJECT}}) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                DeleteState state = new DeleteState();
                assertFalse(delete(modern, room, state, items, null));
                assertFalse(room.trace.contains("eval:legacy"));
                assertTrue(room.committed.isEmpty());
                if (items != null && items.length > 0 && (items[0] == Item.STALE || items[0] == Item.HINTED_MISSING))
                    assertEquals(Arrays.asList("end", "reload:stale", "return-result"), room.trace.subList(room.trace.indexOf("end"), room.trace.size()));
                else assertEquals(Arrays.asList("end", "return-result"), room.trace.subList(room.trace.indexOf("end"), room.trace.size()));
            }
    }
    @Test public void deleteBodyFailureRetainsCarriersButRollsBackWritesAndSkipsAllDispatch() {
        for (Throwable failure : new Throwable[]{new IllegalStateException("body"), new AssertionError("body")})
            for (boolean modern : new boolean[]{false, true}) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                DeleteState state = new DeleteState();
                assertSame(failure, capture(() -> delete(modern, room, state,
                        new Item[]{Item.GMAIL, Item.STALE, Item.LEGACY}, failure)));
                assertEquals(Collections.singletonList("gmail"), state.gmail);
                assertEquals(Collections.singletonList("stale"), state.refresh);
                assertEquals(Collections.singletonList("LEGACY"), state.removed);
                assertTrue(room.committed.isEmpty());
                assertEquals(Arrays.asList("begin", "lookup:GMAIL", "write:gmail-queue", "write:gmail-ui-busy",
                        "lookup:STALE", "lookup:LEGACY", "write:queue:LEGACY", "end"), room.trace);
            }
    }
    @Test public void deleteBeginMarkerAndEndFailuresPreserveCleanupAndSkipConsumers() {
        for (boolean modern : new boolean[]{false, true})
            for (int stage = 0; stage < 3; stage++) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                RuntimeException failure = new IllegalStateException("failure");
                if (stage == 0) room.beginFailure = failure;
                if (stage == 1) room.commitFailure = failure;
                if (stage == 2) room.endFailure = failure;
                assertSame(failure, capture(() -> delete(modern, room, new DeleteState(), new Item[]{Item.GMAIL}, null)));
                assertEquals(stage == 0 ? Arrays.asList("begin", "end") : Arrays.asList("begin", "lookup:GMAIL",
                        "write:gmail-queue", "write:gmail-ui-busy", "commit", "end"), room.trace);
                assertEquals(stage == 2 ? Arrays.asList("gmail-queue", "gmail-ui-busy") : Collections.emptyList(), room.committed);
            }
    }
    @Test public void endFailureMasksBodyAndBeginOnlyWhenLegacyEndWouldRun() {
        for (boolean modern : new boolean[]{false, true})
            for (boolean isDelete : new boolean[]{false, true})
                for (boolean begin : new boolean[]{false, true}) {
                    TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                    RuntimeException original = new IllegalStateException("original");
                    Error end = new AssertionError("end");
                    room.endFailure = end;
                    if (begin) room.beginFailure = original;
                    Throwable actual = capture(() -> {
                        if (isDelete) delete(modern, room, new DeleteState(), new Item[]{Item.GMAIL}, begin ? null : original);
                        else cache(modern, room, new CachedMessage(), "new", begin ? null : original);
                    });
                    assertSame(!isDelete && begin ? original : end, actual);
                    assertEquals(!isDelete && begin ? 0 : 1, Collections.frequency(room.trace, "end"));
                }
    }
}
