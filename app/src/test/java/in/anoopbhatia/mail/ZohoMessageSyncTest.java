package in.anoopbhatia.mail;

import org.junit.Test;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import static org.junit.Assert.*;

public class ZohoMessageSyncTest {
    @Test
    public void sourceReadStateIsFetchedWithoutScanningOtherFolders() throws Exception {
        List<String> requests = new ArrayList<>();
        ZohoApi api = api((url) -> {
            requests.add(url.toString());
            return new ZohoTestHttpsConnection(url, 200, metadata("101", true));
        });
        ZohoMessageSync.Readback result = ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).endsWith("/folders/101/messages/98765/details"));
        assertEquals(Boolean.TRUE, result.found.get(0).seen);
        assertEquals("|101|", result.memberships);
        assertFalse(result.sourceMissing);
    }

    @Test
    public void movedMessageIsRemovedFromOriginalListAndPresentReadInDestination() throws Exception {
        ZohoApi api = api(url -> new ZohoTestHttpsConnection(url,
                url.toString().contains("/folders/202/") ? 200 : 404, metadata("202", true)));
        ZohoMessageSync.Readback result = ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
        assertEquals("|202|", result.memberships);
        assertTrue(result.sourceMissing);
        assertEquals(Boolean.TRUE, result.found.get(0).seen);
        assertEquals(Arrays.asList(2L), visibleFolders(result.memberships, true));
    }

    @Test
    public void successfulSourceGetProvingMoveReplacesOnlySourceWithoutProbing() throws Exception {
        List<String> requests = new ArrayList<>();
        ZohoApi api = api(url -> {
            requests.add(url.toString());
            return new ZohoTestHttpsConnection(url, 200, metadata("202", true));
        });
        ZohoMessageSync.Readback result = ZohoMessageSync.resolve(api, "98765", "101", "|101|999|", folders());
        assertEquals(1, requests.size());
        assertEquals("|999|202|", result.memberships);
        assertTrue(result.sourceMissing);
        assertEquals(1, result.found.size());
        assertEquals("98765", result.found.get(0).messageId);
        assertEquals("202", result.found.get(0).folderId);
        assertEquals(Boolean.TRUE, result.found.get(0).seen);
        assertEquals(Arrays.asList(2L), visibleFolders(result.memberships, true));
    }

    @Test
    public void successfulSourceMoveHandlesLegacyAndAlreadyKnownDestinationWithoutDuplicates() throws Exception {
        for (String current : new String[]{null, "", "|101|202|"}) {
            ZohoApi api = api(url -> new ZohoTestHttpsConnection(url, 200, metadata("202", false)));
            ZohoMessageSync.Readback result = ZohoMessageSync.resolve(api, "98765", "101", current, folders());
            assertEquals("|202|", result.memberships);
            assertTrue(result.sourceMissing);
            assertEquals(Boolean.FALSE, result.found.get(0).seen);
        }
    }

    @Test
    public void confirmedMoveToTrashOrRestoreFromTrashDoesNotIssueDestructiveRequests() throws Exception {
        EntityFolder trash = new EntityFolder("Trash", EntityFolder.TRASH);
        trash.zoho_folder_id = "303";
        List<EntityFolder> mapped = new ArrayList<>(folders());
        mapped.add(trash);
        for (String[] move : new String[][]{{"101", "303"}, {"303", "101"}}) {
            int[] requests = {0};
            ZohoApi api = api(url -> {
                requests[0]++;
                assertTrue(url.toString().endsWith("/folders/" + move[0] + "/messages/98765/details"));
                return new ZohoTestHttpsConnection(url, 200, metadata(move[1], true)) {
                    @Override
                    public void setRequestMethod(String method) {
                        assertEquals("GET", method);
                    }
                };
            });
            ZohoMessageSync.Readback result = ZohoMessageSync.resolve(api, "98765", move[0],
                    "|" + move[0] + "|", mapped);
            assertEquals("|" + move[1] + "|", result.memberships);
            assertTrue(result.sourceMissing);
            assertEquals(1, requests[0]);
        }
    }

    @Test
    public void permanentlyDeletedMessageFromTrashDoesNotRegainInboxOrTrashMembership() throws Exception {
        EntityFolder trash = new EntityFolder("Trash", EntityFolder.TRASH);
        trash.zoho_folder_id = "303";
        List<EntityFolder> mapped = new ArrayList<>(folders());
        mapped.add(trash);
        int[] requests = {0};
        ZohoApi api = api(url -> {
            requests[0]++;
            return new ZohoTestHttpsConnection(url, 404, "{}");
        });
        ZohoMessageSync.Readback result = ZohoMessageSync.resolve(api, "98765", "303", "|101|303|", mapped);
        assertEquals(3, requests[0]);
        assertEquals("|", result.memberships);
        assertTrue(result.sourceMissing);
        assertTrue(result.found.isEmpty());
        assertEquals(Collections.emptyList(), visibleFolders(result.memberships, false));
    }

    @Test
    public void invalidSourceMetadataCannotBeInterpretedAsMoveOrPermanentAbsence() throws Exception {
        for (String body : new String[]{"{}", "{\"data\":{\"messageId\":\"98765\"}}",
                "{\"data\":{\"messageId\":\"11111\",\"folderId\":\"202\"}}",
                "{\"data\":{\"messageId\":\"98765\",\"folderId\":\"invalid\"}}"}) {
            int[] requests = {0};
            ZohoApi api = api(url -> {
                requests[0]++;
                return new ZohoTestHttpsConnection(url, 200, body);
            });
            try {
                ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
                fail("Unverified metadata must leave readback pending");
            } catch (IOException expected) {
                assertFalse(expected instanceof ZohoApi.MembershipChangedException);
            }
            assertEquals(1, requests[0]);
        }
    }

    @Test
    public void changedDestinationDuring404ScanAbortsInsteadOfApplyingPartialReadback() throws Exception {
        ZohoApi api = api(url -> new ZohoTestHttpsConnection(url,
                url.toString().contains("/folders/101/") ? 404 : 200, metadata("303", true)));
        try {
            ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
            fail("A racing destination probe is not a verified complete scan");
        } catch (ZohoApi.MembershipChangedException expected) {
            assertEquals("303", expected.metadata.folderId);
        }
    }

    @Test
    public void all404ClearsVerifiedMembershipButDoesNotRequireDeletingTheCachedRow() throws Exception {
        ZohoApi api = api(url -> new ZohoTestHttpsConnection(url, 404, "{}"));
        ZohoMessageSync.Readback result = ZohoMessageSync.resolve(api, "98765", "101", null, folders());
        assertEquals("|", result.memberships);
        assertTrue(result.found.isEmpty());
        assertTrue(ZohoMessageSync.hasNoRemainingMemberships(result.memberships));
        // Crucially, physical folder=1 must not put this cached row back in Inbox.
        assertEquals(Collections.emptyList(), visibleFolders(result.memberships, false));
    }

    @Test
    public void staleFolderBadRequestIsScannedLikeNotFoundInsteadOfFailingTheReadback() throws Exception {
        // Zoho answers a folder/message pair that no longer matches with HTTP 400.
        List<String> requests = new ArrayList<>();
        ZohoApi api = api(url -> {
            requests.add(url.toString());
            boolean destination = url.toString().contains("/folders/202/");
            return new ZohoTestHttpsConnection(url, destination ? 200 : 400,
                    destination ? metadata("202", false)
                            : "{\"status\":{\"code\":400,\"description\":\"Invalid Input\"}}");
        });
        ZohoMessageSync.Readback result = ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
        assertEquals(2, requests.size());
        assertEquals("|202|", result.memberships);
        assertTrue(result.sourceMissing);
        assertEquals(Boolean.FALSE, result.found.get(0).seen);
        assertEquals(Arrays.asList(2L), visibleFolders(result.memberships, true));
    }

    @Test
    public void allFolderBadRequestsClearMembershipLikeNotFoundAndKeepTheCachedRow() throws Exception {
        ZohoApi api = api(url -> new ZohoTestHttpsConnection(url, 400,
                "{\"status\":{\"code\":400,\"description\":\"Invalid Input\"}}"));
        ZohoMessageSync.Readback result = ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
        assertEquals("|", result.memberships);
        assertTrue(result.sourceMissing);
        assertTrue(result.found.isEmpty());
        assertEquals(Collections.emptyList(), visibleFolders(result.memberships, false));
    }

    @Test
    public void aBadRequestWithoutTheDocumentedDescriptionDoesNotClearMembership() throws Exception {
        ZohoApi api = api(url -> new ZohoTestHttpsConnection(url, 400, "{}"));
        try {
            ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
            fail("A bare 400 must not prove folder absence");
        } catch (ZohoApi.ApiException expected) {
            assertEquals(400, expected.status);
        }
    }

    @Test
    public void badRequestFollowedByTransportFailureStillAbortsPartialReadback() throws Exception {
        ZohoApi api = api(url -> {
            // Documented absence continues the scan; the next probe then fails in transport.
            if (url.toString().contains("/folders/101/"))
                return new ZohoTestHttpsConnection(url, 400,
                        "{\"status\":{\"code\":400,\"description\":\"Invalid Input\"}}");
            throw new IOException("offline during destination verification");
        });
        try {
            ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
            fail("Partial readback must not be applied");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("offline"));
        }
    }

    @Test
    public void authorizationAndServerFailuresDuringTheScanAreNeverTreatedAsAbsence() throws Exception {
        for (int status : new int[]{401, 403, 500}) {
            ZohoApi api = api(url -> new ZohoTestHttpsConnection(url, status, "{}"));
            try {
                ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
                fail("HTTP " + status + " must not prove absence");
            } catch (ZohoApi.ApiException expected) {
                assertEquals(status, expected.status);
            }
        }
    }

    @Test
    public void explicitMembershipOverridesPhysicalSourceButNullLegacyMembershipStillFallsBack() throws Exception {
        assertEquals(Arrays.asList(1L), visibleFolders(null, false));
        assertEquals(Arrays.asList(2L), visibleFolders("|202|", false));
        assertEquals(Arrays.asList(1L, 2L), visibleFolders("|101|202|", false));
    }

    @Test
    public void unknownUnqueriedMembershipIsPreserved() throws Exception {
        ZohoApi api = api(url -> new ZohoTestHttpsConnection(url, 404, "{}"));
        ZohoMessageSync.Readback result = ZohoMessageSync.resolve(api, "98765", "101", "|101|999|", folders());
        assertEquals("|999|", result.memberships);
        assertFalse(ZohoMessageSync.hasNoRemainingMemberships(result.memberships));
    }

    @Test
    public void partialDestinationFailureDoesNotReturnAnIncompleteReadback() throws Exception {
        ZohoApi api = api(url -> {
            if (url.toString().contains("/folders/101/"))
                return new ZohoTestHttpsConnection(url, 404, "{}");
            throw new IOException("offline during destination verification");
        });
        try {
            ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
            fail("Partial readback must not be applied");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("offline"));
        }
    }

    @Test
    public void rateLimitDoesNotProbeOtherFoldersOrInferRemoval() throws Exception {
        int[] requests = {0};
        ZohoApi api = api(url -> {
            requests[0]++;
            return new ZohoTestHttpsConnection(url, 429, "{}");
        });
        try {
            ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
            fail("Expected rate limit");
        } catch (ZohoApi.ApiException expected) {
            assertEquals(429, expected.status);
        }
        assertEquals(1, requests[0]);
    }

    @Test
    public void userMoveQueuedDuringReadbackIsDetectedBeforeApplyingMembership() throws Exception {
        java.util.Set<String> pending = new java.util.HashSet<>();
        DaoOperation operations = (DaoOperation) java.lang.reflect.Proxy.newProxyInstance(
                DaoOperation.class.getClassLoader(), new Class<?>[]{DaoOperation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getOperation") && args.length == 2)
                        return pending.contains((String) args[1]) ? new EntityOperation() : null;
                    throw new AssertionError("Unexpected DAO call " + method);
                });
        assertFalse(ZohoMessageSync.hasPendingLocationChange(operations, 1));
        ZohoApi api = api(url -> {
            pending.add(EntityOperation.MOVE); // user action arrives after preflight, during HTTP
            return new ZohoTestHttpsConnection(url, 200, metadata("101", true));
        });
        ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
        assertTrue(ZohoMessageSync.hasPendingLocationChange(operations, 1));
        for (String name : new String[]{EntityOperation.DELETE, EntityOperation.ZOHO_PERMANENT_DELETE,
                EntityOperation.ZOHO_DELETE_SPAM_PERMANENT}) {
            pending.clear();
            pending.add(name);
            assertTrue(ZohoMessageSync.hasPendingLocationChange(operations, 1));
        }
        pending.clear();
        pending.add(EntityOperation.SEEN);
        assertFalse(ZohoMessageSync.hasPendingLocationChange(operations, 1));
    }

    @Test
    public void deleteOrMoveQueuedDuringProvenFolderChangeStillBlocksLocationOverwrite() throws Exception {
        Set<String> pending = new HashSet<>();
        DaoOperation operations = (DaoOperation) java.lang.reflect.Proxy.newProxyInstance(
                DaoOperation.class.getClassLoader(), new Class<?>[]{DaoOperation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getOperation") && args.length == 2)
                        return pending.contains((String) args[1]) ? new EntityOperation() : null;
                    throw new AssertionError("Unexpected DAO call " + method);
                });
        for (String operation : new String[]{EntityOperation.MOVE, EntityOperation.DELETE,
                EntityOperation.ZOHO_DELETE_SPAM_PERMANENT, EntityOperation.ZOHO_PERMANENT_DELETE}) {
            pending.clear();
            assertFalse(ZohoMessageSync.hasPendingLocationChange(operations, 1));
            ZohoApi api = api(url -> {
                pending.add(operation);
                return new ZohoTestHttpsConnection(url, 200, metadata("303", true));
            });
            ZohoMessageSync.Readback result = ZohoMessageSync.resolve(api, "98765", "101", "|101|", folders());
            assertEquals("|303|", result.memberships);
            assertTrue(ZohoMessageSync.hasPendingLocationChange(operations, 1));
        }
    }

    private static String metadata(String folder, boolean read) {
        return "{\"data\":{\"messageId\":\"98765\",\"folderId\":\"" + folder +
                "\",\"isRead\":" + read + "}}";
    }

    private static List<EntityFolder> folders() {
        EntityFolder inbox = new EntityFolder("Inbox", EntityFolder.INBOX);
        inbox.zoho_folder_id = "101";
        EntityFolder archive = new EntityFolder("Archive", EntityFolder.ARCHIVE);
        archive.zoho_folder_id = "202";
        return Arrays.asList(inbox, archive, archive); // duplicate mappings must not duplicate network reads
    }

    private static ZohoApi api(ZohoApi.ConnectionFactory factory) {
        return new ZohoApi("fixture", "https://www.zohoapis.eu", "account", factory);
    }

    private static List<Long> visibleFolders(String memberships, boolean read) throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement s = db.createStatement()) {
                s.execute("CREATE TABLE folder (id INTEGER, account INTEGER, gmail_label_id TEXT, zoho_folder_id TEXT)");
                s.execute("INSERT INTO folder VALUES (1,7,NULL,'101'), (2,7,NULL,'202')");
                s.execute("CREATE TABLE message (id INTEGER, account INTEGER, folder INTEGER, gmail_id TEXT, labels TEXT, zoho_id TEXT, zoho_folder_ids TEXT, ui_seen INTEGER)");
            }
            try (PreparedStatement s = db.prepareStatement("INSERT INTO message VALUES (1,7,1,NULL,NULL,'98765',?,?)")) {
                s.setString(1, memberships);
                s.setBoolean(2, read);
                s.executeUpdate();
            }
            List<Long> result = new ArrayList<>();
            try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT folder.id FROM folder JOIN message ON " + GmailLabelFolder.SQL_MESSAGE_IN_FOLDER + " ORDER BY folder.id")) {
                while (r.next()) result.add(r.getLong(1));
            }
            // Both folder-list SQL forms must agree; these are production query constants.
            List<Long> targeted = new ArrayList<>();
            String condition = GmailLabelFolder.SQL_MESSAGE_IN_TARGET_FOLDER.replace(":folder", "f.id");
            try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT f.id FROM folder AS f JOIN message ON " + condition + " ORDER BY f.id")) {
                while (r.next()) targeted.add(r.getLong(1));
            }
            assertEquals(result, targeted);
            return result;
        }
    }
}
