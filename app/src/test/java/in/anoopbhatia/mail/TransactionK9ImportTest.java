package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Host recipes, not live SQLite/parser tests; exact production bytes are guarded separately. */
public class TransactionK9ImportTest {
    private static final class Account {
        final String uuid, user; String name; Long id;
        Account(String uuid, String name, String user) { this.uuid = uuid; this.name = name; this.user = user; }
    }
    private static final class Identity {
        final String user; String name, email; Long id, account;
        Identity(String user) { this.user = user; }
    }
    private static final class Pair {
        final String name, email;
        Pair(String name, String email) { this.name = name; this.email = email; }
    }
    private static final class ImportState {
        Account account; Identity identity; final List<Pair> identities;
        ImportState(Account a, Identity i, List<Pair> p) { account = a; identity = i; identities = p; }
    }

    /** A small Room-like transaction: writes are committed only after marker succeeds. */
    private static final class Room {
        final List<String> trace = new ArrayList<>();
        final List<String> committed = new ArrayList<>();
        final List<Identity> insertedIdentities = new ArrayList<>();
        final List<String> cleanupTrace = new ArrayList<>();
        Account existing, expectedAccountAtBegin; RuntimeException beginFailure, markerFailure, endFailure;
        String expectedNameAtBegin;
        final RuntimeException bodyFailure = new IllegalStateException("after-identity-write");
        boolean failAfterIdentityWrite, swallowKnownEndFailure, active, marked;
        private int nextIdentityId = 100;

        Account find(String uuid) { trace.add("find:" + uuid); return existing; }
        long insertAccount(Account a) { assertTrue(active); trace.add("account"); return 41; }
        long insertIdentity(Identity i) {
            assertTrue(active); assertNull("identity id reset before DAO insert", i.id);
            long id = nextIdentityId++;
            trace.add("identity:" + i.name + ":" + i.email + ":" + i.account + ":" + id);
            insertedIdentities.add(i); // The same mutable entity reference is handed to Room each time.
            if (failAfterIdentityWrite) throw bodyFailure;
            return id;
        }
        void begin() {
            trace.add("begin");
            if (expectedAccountAtBegin != null) assertEquals(expectedNameAtBegin, expectedAccountAtBegin.name);
            if (beginFailure != null) throw beginFailure;
            active = true;
        }
        void marker() { trace.add("marker"); if (markerFailure != null) throw markerFailure; marked = true; }
        void end() {
            trace.add("end"); active = false;
            if (marked) for (String event : trace) if (event.equals("account") || event.startsWith("identity:")) committed.add(event);
            if (endFailure != null) {
                if (swallowKnownEndFailure && (endFailure.getMessage() != null && (endFailure.getMessage().contains("no current transaction") || endFailure.getMessage().contains("no transaction is active")))) {
                    trace.add("swallowed-end");
                } else throw endFailure;
            }
        }
        void run(Runnable body) { begin(); try { body.run(); marker(); } finally { end(); } }
        void cleanup(ImportState state, List<Pair> pairs) {
            assertNull(state.account); assertNull(state.identity);
            assertFalse(active);
            if (trace.contains("begin")) {
                String last = trace.get(trace.size() - 1);
                assertTrue("cleanup after end", last.equals("end") || last.equals("swallowed-end"));
            }
            cleanupTrace.add("reset");
            pairs.clear(); cleanupTrace.add("clear");
        }
    }

    private static Throwable failure(Runnable r) { try { r.run(); return null; } catch (Throwable t) { return t; } }
    private static void legacy(Room r, Runnable body) { try { r.begin(); body.run(); r.marker(); } finally { r.end(); } }

    private static void importOne(boolean modern, Room room, ImportState state) {
        importOne(modern, room, state, false);
    }

    private static void importOne(boolean modern, Room room, ImportState state, boolean debug) {
        Account account = state.account; Identity identity = state.identity; List<Pair> pairs = state.identities;
        try {
            if (account != null && identity != null) {
                if (account.name == null || account.name.isEmpty()) account.name = account.user;
                if (debug) account.name = "K9/" + account.name;
                final Account a = account; final Identity i = identity;
                Runnable body = () -> {
                    if (room.find(a.uuid) == null) {
                        a.id = room.insertAccount(a); i.account = a.id;
                        for (Pair pair : pairs) {
                            i.id = null; i.name = pair.name; i.email = pair.email;
                            if (i.name == null || i.name.isEmpty()) i.name = i.user;
                            if (i.email == null || i.email.isEmpty()) i.email = i.user;
                            i.id = room.insertIdentity(i);
                        }
                    }
                };
                if (modern) TransactionScope.run(room::run, room::end, body); else legacy(room, body);
            }
        } finally {
            // Deliberately reset aliases before clearing the source list; both are observable invariants.
            state.account = null; state.identity = null; room.cleanup(state, pairs);
        }
    }

    private static void assertCleanup(ImportState state, List<Pair> pairs) {
        assertNull(state.account); assertNull(state.identity); assertTrue(pairs.isEmpty());
    }

    private static void assertCleanupOrder(Room room) {
        assertEquals(Arrays.asList("reset", "clear"), room.cleanupTrace);
    }

    @Test public void accountPrecedesIdentityAndAssignsForeignKey() {
        for (boolean modern : new boolean[]{false, true}) {
            Room r = new Room(); Account a = new Account("u", "A", "user"); Identity i = new Identity("fallback");
            List<Pair> p = new ArrayList<>(Arrays.asList(new Pair("N", "E"))); ImportState s = new ImportState(a, i, p);
            importOne(modern, r, s);
            assertEquals(Arrays.asList("begin", "find:u", "account", "identity:N:E:41:100", "marker", "end"), r.trace);
            assertEquals(Arrays.asList("account", "identity:N:E:41:100"), r.committed); assertEquals(Long.valueOf(100), i.id);
            assertCleanup(s, p); assertCleanupOrder(r);
        }
    }

    @Test public void eachInsertGetsNextIdAndIncomingIdIsReset() {
        for (boolean modern : new boolean[]{false, true}) {
            Room r = new Room(); Account a = new Account("u", "A", "u"); Identity i = new Identity("user");
            List<Pair> p = new ArrayList<>(Arrays.asList(new Pair("one", "one@x"), new Pair("two", "two@x")));
            ImportState s = new ImportState(a, i, p); importOne(modern, r, s);
            assertEquals(Arrays.asList("identity:one:one@x:41:100", "identity:two:two@x:41:101"), r.committed.subList(1, 3));
            assertEquals(Long.valueOf(101), i.id); assertSame(i, r.insertedIdentities.get(0)); assertSame(i, r.insertedIdentities.get(1));
            assertCleanup(s, p); assertCleanupOrder(r);
        }
    }

    @Test public void incomingIdentityIdsAreNullBeforeBothWrites() {
        for (boolean modern : new boolean[]{false, true}) {
            Room r = new Room(); Account a = new Account("u", "A", "u"); Identity i = new Identity("user");
            i.id = 77L; List<Pair> p = new ArrayList<>(Arrays.asList(new Pair("one", "one@x"), new Pair("two", "two@x")));
            importOne(modern, r, new ImportState(a, i, p));
            assertEquals(Long.valueOf(101), i.id); assertEquals(Long.valueOf(41), i.account); assertEquals(2, r.insertedIdentities.size());
        }
    }

    @Test public void normalizationAndFallbackHappenBeforeBeginAndInsideBody() {
        for (boolean modern : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true}) {
            Room r = new Room(); Account a = new Account("u", "", "account-user"); Identity i = new Identity("identity-user");
            r.expectedAccountAtBegin = a; r.expectedNameAtBegin = debug ? "K9/account-user" : "account-user";
            List<Pair> p = new ArrayList<>(Arrays.asList(new Pair("", null))); importOne(modern, r, new ImportState(a, i, p), debug);
            assertEquals(r.expectedNameAtBegin, a.name); assertEquals("begin", r.trace.get(0));
            assertEquals("identity:identity-user:identity-user:41:100", r.committed.get(1));
        }
    }

    @Test public void existingUuidSkipsWritesButStillNormalizesAndCleans() {
        for (boolean modern : new boolean[]{false, true}) {
            Room r = new Room(); r.existing = new Account("u", "old", "old"); Account a = new Account("u", "", "fallback");
            Identity i = new Identity("i"); List<Pair> p = new ArrayList<>(Arrays.asList(new Pair("N", "E"))); ImportState s = new ImportState(a, i, p);
            importOne(modern, r, s); assertEquals("fallback", a.name); assertEquals(Arrays.asList("begin", "find:u", "marker", "end"), r.trace);
            assertTrue(r.committed.isEmpty()); assertCleanup(s, p); assertCleanupOrder(r);
        }
    }

    @Test public void nullAccountOrIdentityIsANoOpButCleanupStillRuns() {
        for (boolean modern : new boolean[]{false, true}) for (boolean missingAccount : new boolean[]{false, true}) {
            Room r = new Room(); Account a = new Account("u", "", "user"); Identity i = new Identity("i");
            List<Pair> p = new ArrayList<>(Arrays.asList(new Pair("N", "E")));
            ImportState s = new ImportState(missingAccount ? null : a, missingAccount ? i : null, p);
            importOne(modern, r, s, true);
            assertTrue(r.trace.isEmpty()); assertEquals("", a.name);
            assertCleanup(s, p); assertCleanupOrder(r);
        }
    }

    @Test public void injectedFailureAfterIdentityWriteRollsBackButAliasesShowMutation() {
        for (boolean modern : new boolean[]{false, true}) {
            Room r = new Room(); r.failAfterIdentityWrite = true; Account a = new Account("u", "", "account-user"); Identity i = new Identity("identity-user");
            List<Pair> p = new ArrayList<>(Arrays.asList(new Pair("N", "E"))); ImportState s = new ImportState(a, i, p);
            Throwable actual = failure(() -> importOne(modern, r, s)); assertSame(r.bodyFailure, actual);
            assertEquals(Long.valueOf(41), a.id); assertEquals(Long.valueOf(41), i.account); assertNull(i.id); // DAO threw before returning an id.
            assertTrue(r.committed.isEmpty()); assertCleanup(s, p); assertCleanupOrder(r);
        }
    }

    @Test public void beginMarkerAndEndFailuresRetainCommitAndCleanupSemantics() {
        for (boolean modern : new boolean[]{false, true}) for (int phase = 0; phase < 3; phase++) {
            Room r = new Room(); RuntimeException expected = new IllegalStateException("phase" + phase);
            if (phase == 0) r.beginFailure = expected; if (phase == 1) r.markerFailure = expected; if (phase == 2) r.endFailure = expected;
            Account a = new Account("u", "A", "u"); Identity i = new Identity("i"); List<Pair> p = new ArrayList<>(Arrays.asList(new Pair("N", "E"))); ImportState s = new ImportState(a, i, p);
            Throwable actual = failure(() -> importOne(modern, r, s)); assertSame(expected, actual); assertFalse(r.active);
            if (phase < 2) assertTrue(r.committed.isEmpty()); else assertEquals(Arrays.asList("account", "identity:N:E:41:100"), r.committed);
            assertCleanup(s, p); assertCleanupOrder(r);
            assertEquals("end", r.trace.get(r.trace.size() - 1));
        }
    }

    @Test public void cleanupResetPrecedesListClearAfterSuccessAndFailure() {
        for (boolean modern : new boolean[]{false, true}) {
            Room r = new Room(); Account a = new Account("u", "A", "u"); Identity i = new Identity("i"); List<Pair> p = new ArrayList<>(Arrays.asList(new Pair("N", "E"))); ImportState s = new ImportState(a, i, p);
            importOne(modern, r, s); assertCleanup(s, p); assertCleanupOrder(r); assertEquals("end", r.trace.get(r.trace.size() - 1));
            Room failedRoom = new Room(); failedRoom.failAfterIdentityWrite = true;
            List<Pair> failedPairs = new ArrayList<>(Arrays.asList(new Pair("N", "E"))); ImportState failedState = new ImportState(a, i, failedPairs);
            failure(() -> importOne(modern, failedRoom, failedState)); assertCleanup(failedState, failedPairs); assertCleanupOrder(failedRoom); assertTrue(failedRoom.committed.isEmpty());
        }
    }

    @Test public void knownDatabaseEndFailureIsExplicitlySwallowedOnlyWhenConfigured() {
        for (boolean modern : new boolean[]{false, true}) {
            RuntimeException known = new IllegalStateException("no transaction is active"); Room r = new Room(); r.endFailure = known; r.swallowKnownEndFailure = true;
            Account a = new Account("u", "A", "u"); Identity i = new Identity("i"); List<Pair> p = new ArrayList<>(Arrays.asList(new Pair("N", "E"))); ImportState s = new ImportState(a, i, p);
            assertNull(failure(() -> importOne(modern, r, s))); assertEquals(Arrays.asList("account", "identity:N:E:41:100"), r.committed); assertTrue(r.trace.contains("swallowed-end")); assertCleanup(s, p); assertCleanupOrder(r);
            Room unswallowedRoom = new Room(); unswallowedRoom.endFailure = known;
            List<Pair> unswallowedPairs = new ArrayList<>(Arrays.asList(new Pair("N", "E"))); ImportState unswallowedState = new ImportState(a, i, unswallowedPairs);
            assertSame(known, failure(() -> importOne(modern, unswallowedRoom, unswallowedState))); assertCleanup(unswallowedState, unswallowedPairs); assertCleanupOrder(unswallowedRoom);
        }
    }

    @Test public void emptyIdentityListStillInsertsAccountAndPreservesIdentityId() {
        for (boolean modern : new boolean[]{false, true}) {
            Room r = new Room(); Account a = new Account("u", "A", "u"); Identity i = new Identity("i");
            i.id = 77L; List<Pair> p = new ArrayList<>(); ImportState s = new ImportState(a, i, p);
            importOne(modern, r, s);
            assertEquals(Arrays.asList("begin", "find:u", "account", "marker", "end"), r.trace);
            assertEquals(Arrays.asList("account"), r.committed);
            assertEquals(Long.valueOf(41), i.account); assertEquals(Long.valueOf(77), i.id);
            assertCleanup(s, p); assertCleanupOrder(r);
        }
    }

    @Test public void endFailureMasksBeginOrBodyFailureBeforeOuterCleanup() {
        for (boolean modern : new boolean[]{false, true}) for (boolean begin : new boolean[]{false, true}) {
            Room r = new Room(); RuntimeException end = new IllegalStateException("end"); r.endFailure = end;
            if (begin) r.beginFailure = new IllegalStateException("begin"); else r.failAfterIdentityWrite = true;
            Account a = new Account("u", "A", "u"); Identity i = new Identity("i");
            List<Pair> p = new ArrayList<>(Arrays.asList(new Pair("N", "E"))); ImportState s = new ImportState(a, i, p);
            assertSame(end, failure(() -> importOne(modern, r, s)));
            assertTrue(r.committed.isEmpty()); assertCleanup(s, p); assertCleanupOrder(r);
        }
    }

}
