package in.anoopbhatia.mail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZohoMailMutatorTest {
    @Test
    public void replacesOldFolderMembershipWithoutLosingOtherFolders() {
        assertEquals("|22|33|", ZohoMailMutator.replaceFolderId("|11|22|", "11", "33"));
    }

    @Test
    public void replacesDuplicateOldMembershipOnlyOnceWithNewId() {
        assertEquals("|22|33|", ZohoMailMutator.replaceFolderId("|11|22|11|33|", "11", "33"));
    }

    @Test
    public void leavesMembershipsWithoutOldIdUnchanged() {
        String memberships = "|22|33|";
        assertEquals(memberships, ZohoMailMutator.replaceFolderId(memberships, "11", "44"));
    }

    @Test
    public void sameIdIsAnUnchangedMapping() {
        String memberships = "|11|22|";
        assertEquals(memberships, ZohoMailMutator.replaceFolderId(memberships, "11", "11"));
    }

    @Test
    public void onlyTerminalFailureForSameFolderAndOperationCanBeReplaced() {
        EntityOperation failed = new EntityOperation();
        failed.folder = 9L;
        failed.name = EntityOperation.ZOHO_PURGE_TRASH;
        failed.error = ZohoMailMutator.OUTCOME_UNKNOWN_ERROR;

        assertTrue(ZohoMailMutator.isTerminalFailedEmptyFolderOperation(
                failed, 9L, EntityOperation.ZOHO_PURGE_TRASH));
        assertFalse(ZohoMailMutator.isTerminalFailedEmptyFolderOperation(
                failed, 10L, EntityOperation.ZOHO_PURGE_TRASH));
        assertFalse(ZohoMailMutator.isTerminalFailedEmptyFolderOperation(
                failed, 9L, EntityOperation.ZOHO_PURGE_SPAM));

        failed.state = "executing";
        assertFalse(ZohoMailMutator.isTerminalFailedEmptyFolderOperation(
                failed, 9L, EntityOperation.ZOHO_PURGE_TRASH));
        failed.state = null;
        failed.error = null;
        assertFalse(ZohoMailMutator.isTerminalFailedEmptyFolderOperation(
                failed, 9L, EntityOperation.ZOHO_PURGE_TRASH));
    }

    @Test
    public void unchangedMembershipNeedsOnlyOneMetadataRequest() throws Exception {
        List<String> requests = new ArrayList<>();
        ZohoApi api = metadataApi(requests, "11", false);
        assertNull(ZohoMailMutator.findMembership(api, "99", "11",
                Arrays.asList(folder("11"), folder("22")), "|11|"));
        assertEquals(1, requests.size());
    }

    @Test
    public void staleSourceIsReplacedByPositivelyConfirmedDestination() throws Exception {
        List<String> requests = new ArrayList<>();
        ZohoApi api = metadataApi(requests, "22", false);
        assertEquals(Arrays.asList("22", "44"), ZohoMailMutator.findMembership(api, "99", "11",
                Arrays.asList(folder("11"), folder("22"), folder("22"), folder("33")), "|11|44|"));
        assertEquals("Source and duplicate mappings must not be probed twice", 3, requests.size());
        for (String request : requests)
            assertTrue(request.endsWith("/messages/99/details"));
    }

    @Test
    public void noPositiveDestinationMustNotEraseLocalMemberships() throws Exception {
        assertNull(ZohoMailMutator.findMembership(metadataApi(new ArrayList<>(), null, false),
                "99", "11", Arrays.asList(folder("11"), folder("22")), "|11|"));
    }

    @Test
    public void source200ProvingDifferentFolderPreservesUnqueriedMembershipsWithoutReplay() throws Exception {
        for (String current : new String[]{"|11|44|", "|11|22|44|"}) {
            List<ZohoTestHttpsConnection> requests = new ArrayList<>();
            ZohoApi api = new ZohoApi("fixture", "https://www.zohoapis.com", "1", url -> {
                ZohoTestHttpsConnection connection = new ZohoTestHttpsConnection(url, 200,
                        "{\"data\":{\"messageId\":\"99\",\"folderId\":\"22\"}}");
                requests.add(connection);
                return connection;
            });
            assertEquals(Arrays.asList("22", "44"), ZohoMailMutator.findMembership(api, "99", "11",
                    Arrays.asList(folder("11"), folder("22"), folder("33")), current));
            assertEquals(1, requests.size());
            assertEquals("GET", requests.get(0).getRequestMethod());
            assertTrue(requests.get(0).getURL().toString().endsWith("/folders/11/messages/99/details"));
            assertEquals(0, requests.get(0).requestBody.size());
        }
    }

    @Test
    public void source200WithWrongMessageIdCannotReconcileEvenWithValidDestination() throws Exception {
        int[] requests = {0};
        ZohoApi api = new ZohoApi("fixture", "https://www.zohoapis.com", "1", url -> {
            requests[0]++;
            return new ZohoTestHttpsConnection(url, 200,
                    "{\"data\":{\"messageId\":\"100\",\"folderId\":\"22\"}}");
        });
        try {
            ZohoMailMutator.findMembership(api, "99", "11", Arrays.asList(folder("11"), folder("22")), "|11|");
            fail("Wrong message must not prove source absence");
        } catch (IOException expected) {
            assertFalse(expected instanceof ZohoApi.MembershipChangedException);
        }
        assertEquals(1, requests[0]);
    }

    @Test
    public void staleSourceBadRequestIsReplacedByConfirmedDestination() throws Exception {
        ZohoApi api = new ZohoApi("fixture", "https://www.zohoapis.com", "1", url -> {
            if (url.toString().contains("/folders/22/"))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"data\":{\"messageId\":\"99\",\"folderId\":\"22\"}}");
            return new ZohoTestHttpsConnection(url, 400,
                    "{\"status\":{\"code\":400,\"description\":\"Invalid Input\"}}");
        });
        assertEquals(Arrays.asList("22", "44"), ZohoMailMutator.findMembership(api, "99", "11",
                Arrays.asList(folder("11"), folder("22")), "|11|44|"));
    }

    @Test
    public void serverFailureDuringStaleSourceScanStillAbortsReconciliation() throws Exception {
        ZohoApi api = new ZohoApi("fixture", "https://www.zohoapis.com", "1", url -> {
            // Source absence is the documented 400 "Invalid Input"; the destination probe
            // then fails hard and must abort the whole reconciliation.
            if (url.toString().contains("/folders/11/"))
                return new ZohoTestHttpsConnection(url, 400,
                        "{\"status\":{\"code\":400,\"description\":\"Invalid Input\"}}");
            return new ZohoTestHttpsConnection(url, 500, "{}");
        });
        try {
            ZohoMailMutator.findMembership(api, "99", "11",
                    Arrays.asList(folder("11"), folder("22")), "|11|");
            fail("A server failure must not confirm absence");
        } catch (ZohoApi.ApiException expected) {
            assertEquals(500, expected.status);
        }
    }

    @Test
    public void partialReadbackFailureMustNotCommitAPartialMembershipSet() throws Exception {
        try {
            ZohoMailMutator.findMembership(metadataApi(new ArrayList<>(), "22", true),
                    "99", "11", Arrays.asList(folder("11"), folder("22"), folder("33")), "|11|33|");
            fail("Incomplete verification must not produce a membership replacement");
        } catch (IOException expected) {
            assertEquals("network unavailable", expected.getMessage());
        }
    }

    private static ZohoApi metadataApi(List<String> requests, String confirmed, boolean failLast) {
        return new ZohoApi("fixture", "https://www.zohoapis.com", "1", url -> {
            String path = url.toString();
            requests.add(path);
            if (failLast && path.contains("/folders/33/"))
                throw new IOException("network unavailable");
            if (confirmed != null && path.contains("/folders/" + confirmed + "/"))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"status\":{\"code\":200},\"data\":{\"messageId\":\"99\",\"folderId\":\"" + confirmed + "\"}}");
            return new ZohoTestHttpsConnection(url, 404, "{\"status\":{\"code\":404}}");
        });
    }

    private static EntityFolder folder(String remoteId) {
        EntityFolder folder = new EntityFolder("folder", EntityFolder.USER);
        folder.zoho_folder_id = remoteId;
        return folder;
    }
}
