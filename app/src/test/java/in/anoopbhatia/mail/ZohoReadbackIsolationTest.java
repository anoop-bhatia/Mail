package in.anoopbhatia.mail;

import org.json.JSONException;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Executable readback regressions plus contracts for the Android/Room worker wiring. */
public class ZohoReadbackIsolationTest {
    @Test
    public void ambiguousReaderDoesNotPreventIndependentDeletedMessageReconciliation() throws Exception {
        List<String> paths = new ArrayList<>();
        ZohoApi api = new ZohoApi("fixture", "https://www.zohoapis.eu", "1", url -> {
            paths.add(url.getPath());
            boolean ambiguousReader = url.getPath().contains("/messages/98765/");
            return getOnly(url, ambiguousReader ? 200 : 404,
                    ambiguousReader ? "{\"data\":{}}" : "{}");
        });
        String[] ambiguousMemberships = {"|101|"};
        assertFalse(ZohoMessageSync.tryReadback(() -> {
            ZohoMessageSync.Readback result = ZohoMessageSync.resolve(api, "98765", "101",
                    ambiguousMemberships[0], folders());
            ambiguousMemberships[0] = result.memberships;
        }));
        assertEquals("|101|", ambiguousMemberships[0]);

        // A different message, permanently deleted by another client from Trash.
        ZohoMessageSync.Readback[] deleted = {null};
        assertTrue(ZohoMessageSync.tryReadback(() -> deleted[0] =
                ZohoMessageSync.resolve(api, "98766", "303", "|101|202|303|", folders())));
        assertEquals("|", deleted[0].memberships);
        assertTrue(deleted[0].found.isEmpty());
        assertTrue(deleted[0].sourceMissing);
        assertEquals(4, paths.size()); // one ambiguous GET, then each mapped folder once
        assertTrue(paths.get(1).endsWith("/folders/303/messages/98766/details"));
        for (String path : paths)
            assertTrue(path.endsWith("/details"));
    }

    @Test
    public void invalidOrMismatchedIdsNeverAlterMemberships() throws Exception {
        for (String body : new String[]{
                "{\"data\":{\"messageId\":\"not-numeric\",\"folderId\":\"202\"}}",
                "{\"data\":{\"messageId\":\"98766\",\"folderId\":\"202\"}}",
                "{\"data\":{\"messageId\":\"98765\",\"folderId\":\"not-numeric\"}}",
                "{\"data\":{\"messageId\":\"98765\",\"folderId\":\"9223372036854775808\"}}",
                "{\"data\":{}}", "{}"}) {
            String[] memberships = {"|101|202|"};
            ZohoApi api = new ZohoApi("fixture", "https://www.zohoapis.eu", "1",
                    url -> getOnly(url, 200, body));
            assertFalse(ZohoMessageSync.tryReadback(() -> memberships[0] =
                    ZohoMessageSync.resolve(api, "98765", "101", memberships[0], folders()).memberships));
            assertEquals("|101|202|", memberships[0]);
        }
    }

    @Test
    public void ambiguityAfterSource404DoesNotCommitPartialRemoval() throws Exception {
        ZohoApi api = new ZohoApi("fixture", "https://www.zohoapis.eu", "1", url -> {
            boolean source = url.getPath().contains("/folders/101/");
            return getOnly(url, source ? 404 : 200, source ? "{}" : "{\"data\":{}}");
        });
        String[] memberships = {"|101|202|303|"};
        assertFalse(ZohoMessageSync.tryReadback(() -> memberships[0] =
                ZohoMessageSync.resolve(api, "98765", "101", memberships[0], folders()).memberships));
        assertEquals("|101|202|303|", memberships[0]);
    }

    @Test
    public void positivelyProvenMoveStillReconcilesThroughIsolationBoundary() throws Exception {
        ZohoApi api = new ZohoApi("fixture", "https://www.zohoapis.eu", "1", url ->
                getOnly(url, 200, "{\"data\":{\"messageId\":\"98765\",\"folderId\":\"303\"}}"));
        ZohoMessageSync.Readback[] result = {null};
        assertTrue(ZohoMessageSync.tryReadback(() -> result[0] =
                ZohoMessageSync.resolve(api, "98765", "101", "|101|202|", folders())));
        assertEquals("|202|303|", result[0].memberships);
        assertTrue(result[0].sourceMissing);
    }

    @Test
    public void authThrottleTransportServerAndParseFailuresStillReachWorkerPolicy() throws Exception {
        for (IOException failure : new IOException[]{new IOException("transport"),
                new ZohoApi.ApiException(401, 0), new ZohoApi.ApiException(403, 1000),
                new ZohoApi.ApiException(429, 1000), new ZohoApi.ApiException(500, 0)}) {
            try {
                ZohoMessageSync.tryReadback(() -> { throw failure; });
                fail("Must propagate non-semantic failure");
            } catch (IOException expected) {
                assertSame(failure, expected);
            }
        }
        JSONException malformed = new JSONException("malformed");
        try {
            ZohoMessageSync.tryReadback(() -> { throw malformed; });
            fail("Must propagate JSON failure");
        } catch (JSONException expected) {
            assertSame(malformed, expected);
        }
        android.os.OperationCanceledException cancelled = new android.os.OperationCanceledException();
        try {
            ZohoMessageSync.tryReadback(() -> { throw cancelled; });
            fail("Must propagate cancellation");
        } catch (android.os.OperationCanceledException expected) {
            assertSame(cancelled, expected);
        }
    }

    @Test
    public void onlyZohoReadOnlyReaderRequestsCanBePassedByFolderScan() {
        EntityOperation reader = operation(NativeReaderState.OPERATION);
        assertFalse(NativeFolderState.hasBlockingOperations(true, Collections.emptyList()));
        assertFalse(NativeFolderState.hasBlockingOperations(true, Arrays.asList(reader, reader)));
        assertTrue(NativeFolderState.hasBlockingOperations(false, Arrays.asList(reader)));
        for (String name : new String[]{EntityOperation.SEEN, EntityOperation.FLAG,
                EntityOperation.MOVE, EntityOperation.DELETE, EntityOperation.ZOHO_PERMANENT_DELETE,
                EntityOperation.ZOHO_DELETE_SPAM_PERMANENT, EntityOperation.ZOHO_PURGE_TRASH,
                EntityOperation.ZOHO_PURGE_SPAM, EntityOperation.BODY, "unknown"}) {
            assertTrue(NativeFolderState.hasBlockingOperations(true, Arrays.asList(operation(name))));
            assertTrue(NativeFolderState.hasBlockingOperations(true, Arrays.asList(reader, operation(name))));
        }
        assertFalse(ZohoOperationWakeup.isZohoWriteOperation(NativeReaderState.OPERATION));
    }

    @Test
    public void workerWiringKeepsRetryDurableBeforeContinuingIndependentWork() throws Exception {
        String reader = source("NativeReaderState");
        int isolated = reader.indexOf("else if (!ZohoMessageSync.tryReadback(");
        int next = reader.indexOf("continue;", isolated);
        int completed = reader.indexOf("db.operation().deleteOperation(operation.id)", isolated);
        assertTrue(isolated >= 0 && next > isolated && completed > next);
        String defer = reader.substring(reader.indexOf("static void deferZohoRefresh"),
                reader.indexOf("static void process("));
        assertTrue(defer.contains("db.runInTransaction(() ->"));
        assertTrue(defer.contains("getOperation(message.id, OPERATION) != null"));
        assertTrue(defer.contains("operation.name = OPERATION"));
        assertTrue(defer.contains("insertOperation(operation)"));
        assertFalse(defer.contains("ServiceSynchronize.eval"));

        String folder = source("NativeFolderState");
        int handoff = folder.indexOf("NativeReaderState.deferZohoRefresh");
        int advance = folder.indexOf("lastId = message.id", handoff);
        assertTrue(handoff >= 0 && advance > handoff);
        assertTrue(folder.contains("hasBlockingOperations(account.isZohoApi(), pending)"));
        assertTrue(folder.contains("if (account.isZohoApi() && !pending.isEmpty())"));
        assertTrue(folder.contains("morePending |= !blocked"));
        assertEquals(10, NativeFolderState.BATCH_SIZE);

        String service = source("ServiceSynchronize");
        int zoho = service.indexOf("private void monitorZohoAccount");
        int writes = service.indexOf("ZohoMailMutator.process", zoho);
        int readers = service.indexOf("NativeReaderState.process", writes);
        int scan = service.indexOf("NativeFolderState.process", readers);
        int pages = service.indexOf("syncRequestedZohoFolder(", scan);
        assertTrue(writes > zoho && readers > writes && scan > readers && pages > scan);
    }

    @Test
    public void deferredReadsRemainWakeableButCannotCauseImmediatePageRetryLoop() throws Exception {
        String service = source("ServiceSynchronize");
        int gate = service.indexOf("private static boolean hasRequestedZohoFolderSync");
        int pageGate = service.indexOf("private static boolean hasRequestedZohoFolderPages");
        String wake = service.substring(gate, pageGate);
        assertTrue(wake.contains("NativeReaderState.hasPending"));
        assertTrue(wake.contains("NativeFolderState.hasPending"));
        int continuation = service.lastIndexOf("boolean morePending =", gate);
        String more = service.substring(continuation, service.indexOf(';', continuation));
        assertTrue(more.contains("hasRequestedZohoFolderPages"));
        assertFalse(more.contains("hasRequestedZohoFolderSync"));
        assertFalse(more.contains("NativeReaderState"));
        assertFalse(more.contains("NativeFolderState"));
        assertEquals(7500L, ZohoFolderPageBatch.successfulPassWaitMs(false, 7500L));
    }

    @Test
    public void permanentDeleteUnknownOutcomeRemainsQuarantinedRatherThanReplayed() throws Exception {
        String mutator = source("ZohoMailMutator");
        int stateGuard = mutator.indexOf("if (current.state != null)");
        int errorGuard = mutator.indexOf("if (current.error != null)");
        int send = mutator.indexOf("api.deleteMessage(");
        assertTrue(stateGuard >= 0 && errorGuard > stateGuard && send > errorGuard);
        assertTrue(mutator.substring(stateGuard, errorGuard).contains("markUnknown(db, current)"));
        assertTrue(mutator.substring(stateGuard, errorGuard).contains("continue;"));
        assertTrue(mutator.substring(errorGuard, send).contains("continue;"));
        assertTrue(mutator.indexOf("deleteLocally(db, current, message)", send) > send);
        assertEquals(ZohoMutationRecovery.FailureAction.QUARANTINE_UNKNOWN,
                ZohoMutationRecovery.classify(new IOException("connection lost")));
    }

    private static ZohoTestHttpsConnection getOnly(java.net.URL url, int status, String body) {
        return new ZohoTestHttpsConnection(url, status, body) {
            @Override
            public void setRequestMethod(String method) {
                assertEquals("GET", method);
            }
        };
    }

    private static EntityOperation operation(String name) {
        EntityOperation operation = new EntityOperation();
        operation.name = name;
        return operation;
    }

    private static List<EntityFolder> folders() {
        List<EntityFolder> result = new ArrayList<>();
        String[] types = {EntityFolder.INBOX, EntityFolder.ARCHIVE, EntityFolder.TRASH};
        String[] ids = {"101", "202", "303"};
        for (int i = 0; i < ids.length; i++) {
            EntityFolder folder = new EntityFolder(types[i], types[i]);
            folder.zoho_folder_id = ids[i];
            result.add(folder);
        }
        return result;
    }

    private static String source(String name) throws IOException {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve(name + ".java"));
    }
}
