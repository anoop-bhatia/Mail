package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class NativeReaderStateTest {
    @Test
    public void supportsGraphAndZohoButNeverGmailOrLegacy() {
        EntityMessage message = new EntityMessage();
        message.account = 7L;
        message.graph_id = "graph-message";
        message.zoho_id = "123456";
        message.gmail_id = "gmail-message";
        EntityAccount account = new EntityAccount();
        account.id = 7L;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        assertTrue(NativeReaderState.supports(account, message));
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        assertTrue(NativeReaderState.supports(account, message));
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        assertFalse(NativeReaderState.supports(account, message));
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        account.protocol = EntityAccount.TYPE_IMAP;
        assertFalse(NativeReaderState.supports(account, message));
        account.protocol = EntityAccount.TYPE_POP;
        assertFalse(NativeReaderState.supports(account, message));
    }

    @Test
    public void rejectsWrongAccountMissingIdsAndLocalDrafts() {
        EntityAccount account = new EntityAccount();
        account.id = 7L;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        EntityMessage message = new EntityMessage();
        message.account = 8L;
        message.graph_id = "remote";
        assertFalse(NativeReaderState.supports(account, message));
        message.account = 7L;
        message.graph_id = "";
        assertFalse(NativeReaderState.supports(account, message));
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        assertFalse(NativeReaderState.supports(account, message));
        message.zoho_id = "not-a-remote-id";
        assertFalse(NativeReaderState.supports(account, message));
        assertFalse(NativeReaderState.supports(account, null));
        assertFalse(NativeReaderState.supports(null, message));
    }

    @Test
    public void backQueuesBeforeNavigationAndRetainsAnEmptyReaderAnchor() throws Exception {
        String source = source("FragmentMessages");
        String exit = source.substring(source.indexOf("private void queueReaderStateCheck()"),
                source.indexOf("private void navigate("));
        assertTrue(exit.contains("viewType == AdapterMessage.ViewType.THREAD && !readerStateQueued"));
        assertTrue(exit.contains("readerIds.add(id)"));
        assertTrue(exit.contains("args.putLong(\"source\", folder)"));
        assertTrue(exit.contains("NativeReaderState.queue(context"));
        // One readback per message the user opened, not per row of the thread.
        assertTrue(source.contains("readerStateIds.add(message.id)"));
        assertTrue(exit.contains("for (Long opened : readerStateIds)"));
        assertFalse("A reader exit must not read back the whole thread",
                exit.contains("adapter.getItemAtPosition(pos)"));
        assertTrue(source.contains("if (isRemoving())"));
        assertTrue(source.contains("queueReaderStateCheck();"));
        String back = source.substring(source.indexOf("private OnBackPressedCallback backPressedCallback"));
        assertTrue(back.indexOf("handleExit();") < back.indexOf("onBackPressedFragment();"));
    }

    @Test
    public void gmailReaderExitWakesUnifiedAccountAndRefreshesRealFolderWithoutPerMessageReadback() throws Exception {
        String reader = source("NativeReaderState");
        // Gmail history already reports other-client flags and moves, so the exit costs the same
        // account/folder refresh an IMAP reader exit costs. Unified inbox IDs can be synthetic.
        assertTrue(reader.contains("if (account.isGmailApi())"));
        assertTrue(reader.contains("EntityOperation.sync(context, sourceFolder, true)"));
        assertTrue(reader.contains("ServiceSynchronize.eval(context, account, \"native-reader-exit\")"));
        int refreshLoop = reader.indexOf("for (long account : refreshOnly)");
        int wake = reader.indexOf("accounts.add(account);", refreshLoop);
        int folderLookup = reader.indexOf("EntityFolder folder = db.folder().getFolder(sourceFolder);", refreshLoop);
        assertTrue("The account must be woken even when a unified source ID has no folder row",
                refreshLoop >= 0 && wake > refreshLoop && wake < folderLookup);
        assertTrue("A Gmail message must never queue the provider readback",
                reader.indexOf("if (!supports(account, message))") <
                        reader.indexOf("db.operation().insertOperation(operation)"));
    }

    @Test
    public void checksRunOnAccountWorkersAfterWritesAndWakeThroughExistingBackoffGate() throws Exception {
        String source = source("ServiceSynchronize").replaceAll("\\s+", " ");
        assertTrue(source.contains("GraphMailMutator.process(this, db, account, api, state); NativeReaderState.process(this, db, account, state, api, null);"));
        assertTrue(source.contains("ZohoMailMutator.process(this, db, account, api, state); NativeReaderState.process(this, db, account, state, null, api);"));
        for (String provider : new String[]{"Graph", "Zoho"}) {
            String gate = source.substring(source.indexOf("private static boolean hasRequested" + provider + "FolderSync"));
            gate = gate.substring(0, gate.indexOf("}"));
            assertTrue(gate.contains("NativeReaderState.hasPending(db, accountId)"));
        }
        assertTrue(source.contains("released = state.releaseUnlessBackingOff();"));
        // queue() must commit the durable request before waking the account worker.
        String reader = source("NativeReaderState");
        int commit = commitBoundary(reader, reader.indexOf("db.runInTransaction(() -> {"));
        assertTrue("The queued request must be inserted inside the transaction",
                reader.indexOf("db.operation().insertOperation(operation)") < commit);
        assertTrue("The worker must be woken only after the request is committed",
                commit < reader.indexOf("ServiceSynchronize.eval("));
        assertFalse(reader.contains("setMessageRead("));
    }

    @Test
    public void corruptRequestArgsFallBackWithoutTrappingTheWorkerInAReadbackLoop() {
        assertEquals(12L, NativeReaderState.sourceFolder("[12]", 7L));
        assertEquals(7L, NativeReaderState.sourceFolder("[]", 7L));
        assertEquals(7L, NativeReaderState.sourceFolder("broken", 7L));
        assertEquals(7L, NativeReaderState.sourceFolder(null, 7L));
    }

    @Test
    public void lateUserLocationIntentIsRecheckedInsideBothProviderWriteTransactions() throws Exception {
        String graph = source("GraphMessageSync");
        int transaction = graph.indexOf("db.callInTransaction(() -> {");
        int commit = commitBoundary(graph, transaction);
        int guard = graph.indexOf("!GraphFolderMembership.hasPendingLocationChange(db.operation(), message.id)");
        int write = graph.indexOf("db.message().setMessageFolder(message.id, actual.id)");
        assertTrue("Graph location guard and write must share the transaction",
                transaction < guard && guard < write && write < commit);
        String zoho = source("ZohoMessageSync");
        transaction = zoho.indexOf("db.runInTransaction(() -> {");
        commit = commitBoundary(zoho, transaction);
        guard = zoho.indexOf("!hasPendingLocationChange(db.operation(), message.id)");
        write = zoho.indexOf("db.message().setMessageZohoFolderIds(message.id, readback.memberships)");
        assertTrue("Zoho location guard and write must share the transaction",
                transaction < guard && guard < write && write < commit);
    }

    /**
     * Raw beginTransaction/endTransaction pairs no longer exist in production code. Return the
     * index just past the closing "});" of the helper transaction opened at transactionStart, so
     * these guards measure a real commit boundary instead of comparing against -1.
     */
    private static int commitBoundary(String source, int transactionStart) {
        assertTrue("Missing transaction helper call", transactionStart >= 0);
        int open = source.indexOf("{", transactionStart);
        assertTrue("Missing transaction body", open > transactionStart);
        int lineStart = source.lastIndexOf('\n', transactionStart) + 1;
        int indent = 0;
        while (lineStart + indent < source.length() && source.charAt(lineStart + indent) == ' ')
            indent++;
        String close = "\n" + " ".repeat(indent) + "});";
        int end = source.indexOf(close, open);
        assertTrue("Missing transaction commit boundary", end > open);
        return end + close.length();
    }

    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve(name + ".java"));
    }
}
