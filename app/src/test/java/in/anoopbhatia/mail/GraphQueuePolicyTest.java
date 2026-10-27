package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Service integration contracts alongside GraphOperationWakeup's executable wakeup tests. */
public class GraphQueuePolicyTest {
    @Test
    public void graphReaderOpenAndExitWakeOnlyTheirOwningAccount() throws Exception {
        String expand = section(source("FragmentMessages"), "private void handleExpand(long id)",
                "private void handleAutoClose()").replaceAll("\\s+", " ");
        assertGraphExpansionScope(expand);
        assertTrue(source("NativeReaderState").contains(
                "ServiceSynchronize.eval(context, account, \"native-reader-exit\")"));
        String service = source("ServiceSynchronize");
        assertTrue(service.contains("if (account > 0 && !current.accountState.id.equals(account))"));
        assertTrue(service.contains("? Collections.singletonList(accountId)"));
    }

    private static void assertGraphExpansionScope(String expand) {
        // The completion carrier was already present at d4592f6. Pin its entire
        // account dataflow, not the pre-migration name of a mutable outer local.
        assertTrue(expand.contains("Long targetGraphAccount = null;"));
        assertTrue(expand.contains("if (account.isGraphApi()) targetGraphAccount = account.id;"));
        String commit = "return TransactionResult.commit(android.util.Pair.create(targetReload, targetGraphAccount));";
        String guard = "if (completed == null) return null;";
        String restore = "Long graphAccount = completed.second;";
        String wake = "ServiceSynchronize.eval(context, graphAccount, \"expand\")";
        int committed = expand.indexOf(commit);
        int guarded = expand.indexOf(guard);
        int restored = expand.indexOf(restore);
        int awakened = expand.indexOf(wake);
        assertTrue(committed >= 0 && guarded > committed && restored > guarded && awakened > restored);
        assertTrue(expand.contains("if (reload == null) { if (!queuedGmailRead) " + wake));
        assertFalse(expand.contains("ServiceSynchronize.eval(context, \"expand\")"));
    }

    @Test
    public void graphExpansionContractRejectsDroppedOrMisroutedAccountAndRollbackWakeup() throws Exception {
        String expand = section(source("FragmentMessages"), "private void handleExpand(long id)",
                "private void handleAutoClose()").replaceAll("\\s+", " ");
        for (String changed : new String[]{
                expand.replace("targetGraphAccount = account.id;", "targetGraphAccount = message.id;"),
                expand.replace("Pair.create(targetReload, targetGraphAccount)", "Pair.create(targetReload, null)"),
                expand.replace("Long graphAccount = completed.second;", "Long graphAccount = completed.first;"),
                expand.replace("if (completed == null) return null;", ""),
                expand.replace("ServiceSynchronize.eval(context, graphAccount, \"expand\")",
                        "ServiceSynchronize.eval(context, \"expand\")")}) {
            boolean rejected = false;
            try {
                assertGraphExpansionScope(changed);
            } catch (AssertionError expected) {
                rejected = true;
            }
            assertTrue("Account capture, result transport, rollback guard and scoped wake must all be pinned", rejected);
        }
    }

    @Test
    public void explicitGraphWriteEvalDoesNotDependOnlyOnLiveDataCountIncreases() throws Exception {
        String service = source("ServiceSynchronize");
        String gate = section(service, "private static boolean hasRequestedGraphFolderSync(",
                "private static boolean hasRequestedGraphReconciliation(");
        assertTrue(gate.contains("GraphOperationWakeup.hasPendingWrite(db.operation().getOperations(accountId))"));
        String wake = section(service, "private void wakeRequestedApiFolderSync(",
                "private static boolean hasRequestedGraphFolderSync(");
        assertTrue(wake.contains("return hasRequestedGraphFolderSync(db, id)"));
        assertTrue(wake.contains("released = state.releaseUnlessBackingOff()"));
    }

    @Test
    public void lateReaderAndMembershipRefreshWorkIsCheckedAfterImportsBeforePollSleep() throws Exception {
        String graph = section(source("ServiceSynchronize"), "private void monitorGraphAccount(",
                "private int syncRequestedGraphFolders(");
        int reader = graph.indexOf("NativeReaderState.process(");
        int requested = graph.indexOf("syncRequestedGraphFolders(");
        int selected = graph.indexOf("GraphMailReceiver.syncOtherSelectedFolders(");
        int recheck = graph.indexOf("hasRequestedGraphReconciliation(db, account.id) ? 0 : pollDelay");
        int failure = graph.indexOf("} catch (Throwable ex)");
        assertTrue(reader >= 0 && requested > reader && selected > requested);
        assertTrue("Requests created after their processing stage must not wait for a poll",
                recheck > selected && failure > recheck);
        assertTrue(graph.indexOf("state.acquire(waitDelay, false)") > failure);
        String ready = section(source("ServiceSynchronize"),
                "private static boolean hasRequestedGraphReconciliation(", "private void onReload(");
        assertTrue(ready.contains("NativeReaderState.hasPending(db, accountId)"));
        assertTrue(ready.contains("EntityOperation.SYNC"));
        assertTrue(ready.contains("GraphMailReceiver.isRequestedFolderSync("));
        assertFalse("Blocked scans must not create an immediate-pass busy loop", ready.contains("NativeFolderState"));
        assertFalse("Unknown writes must not create an immediate-pass busy loop", ready.contains("hasPendingWrite"));
    }

    @Test
    public void immediateContinuationDoesNotReplaceNetworkOrServerRetryWaits() throws Exception {
        String graph = section(source("ServiceSynchronize"), "private void monitorGraphAccount(",
                "private int syncRequestedGraphFolders(");
        String failure = graph.substring(graph.indexOf("} catch (Throwable ex)"));
        assertFalse(failure.contains("hasRequestedGraphReconciliation"));
        assertTrue(failure.contains("ApiNetworkHibernation.getTransportRetryDelayMs(transportFailures)"));
        assertTrue(failure.contains("RetryAfter.isPresent(retryAfter) ? Math.max(1000L, retryAfter)"));
        assertTrue(failure.contains("getFailureRetryDelayMs(failureDelay)"));
        assertTrue(failure.contains("waitDelay = delay;"));
        assertTrue(failure.contains("state.acquireRateLimitWait(waitDelay)"));
    }

    @Test
    public void blockedScansAndAlreadyQueuedRefreshesDoNotCreateMoreImmediateWork() throws Exception {
        String scan = source("NativeFolderState");
        assertTrue("A blocked scan must not report progress for another immediate pass",
                scan.contains("morePending |= !blocked;"));
        String sync = section(source("EntityOperation"),
                "private static boolean sync(Context context, long fid, boolean foreground, boolean force,",
                "static boolean canQueueSubscribe(");
        assertTrue(sync.contains("int syncCount = db.operation().getOperationCount(fid, SYNC)"));
        int dedup = sync.indexOf("if (syncCount == 0)");
        int insert = sync.indexOf("db.operation().insertOperation(operation)");
        assertTrue(dedup >= 0 && insert > dedup);
        String membership = section(source("GraphMailReceiver"),
                "private static EntityMessage applyCurrentMembership(", "private static void logMembership(");
        assertTrue(membership.contains("if (relocated && GraphFolderMembership.hasPendingLocationChange"));
        assertTrue(membership.contains("if (relocated) {"));
        assertTrue(membership.indexOf("GraphFolderMembership.relocate(") <
                membership.indexOf("GraphMessageSync.refresh(context, source)"));
    }

    private static String section(String source, String start, String end) {
        int first = source.indexOf(start);
        int last = source.indexOf(end, first + start.length());
        assertTrue("Missing section " + start, first >= 0 && last > first);
        return source.substring(first, last);
    }

    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve(name + ".java"));
    }
}
