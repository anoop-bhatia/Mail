package in.anoopbhatia.mail;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GraphFolderMembershipTest {
    @Test
    public void currentParentBeatsListedSourceForMailboxWideGet() {
        EntityFolder listed = folder(10L, "source");
        EntityFolder actual = folder(11L, "destination");
        DaoFolder folders = (DaoFolder) Proxy.newProxyInstance(
                DaoFolder.class.getClassLoader(), new Class<?>[]{DaoFolder.class},
                (proxy, method, args) -> "getFolderByGraphId".equals(method.getName()) ? actual : null);

        EntityFolder resolved = GraphFolderMembership.resolveFolder(folders, 7L, listed,
                new GraphMailApi.MessageState("message", "destination", null, null));

        assertSame(actual, resolved);
    }

    @Test
    public void missingOrListedParentKeepsSourceFolder() {
        EntityFolder listed = folder(10L, "source");
        DaoFolder folders = (DaoFolder) Proxy.newProxyInstance(
                DaoFolder.class.getClassLoader(), new Class<?>[]{DaoFolder.class},
                (proxy, method, args) -> {
                    throw new AssertionError("No lookup expected");
                });

        assertSame(listed, GraphFolderMembership.resolveFolder(folders, 7L, listed,
                new GraphMailApi.MessageState("message", null, null, null)));
        assertSame(listed, GraphFolderMembership.resolveFolder(folders, 7L, listed,
                new GraphMailApi.MessageState("message", "source", null, null)));
    }

    @Test
    public void targetedRelocationChangesOnlyFolderAndCachedFolder() {
        EntityMessage cached = new EntityMessage();
        cached.id = 42L;
        cached.folder = 10L;
        cached.graph_id = "graph-id";
        cached.extra = "cached body";
        EntityFolder actual = folder(11L, "destination");
        List<Long> updated = new ArrayList<>();
        DaoMessage messages = (DaoMessage) Proxy.newProxyInstance(
                DaoMessage.class.getClassLoader(), new Class<?>[]{DaoMessage.class},
                (proxy, method, args) -> {
                    if ("setMessageFolder".equals(method.getName())) {
                        updated.add((Long) args[0]);
                        updated.add((Long) args[1]);
                        return 1;
                    }
                    return null;
                });

        assertTrue(GraphFolderMembership.isRelocation(cached, actual));
        assertTrue(GraphFolderMembership.relocate(messages, cached, actual));
        assertEquals(java.util.Arrays.asList(42L, 11L), updated);
        assertEquals(Long.valueOf(11L), cached.folder);
        assertEquals("cached body", cached.extra);
    }

    @Test
    public void nonGraphOrSameFolderIsNotRelocation() {
        EntityFolder actual = folder(11L, "destination");
        EntityMessage nonGraph = new EntityMessage();
        nonGraph.folder = 10L;
        assertFalse(GraphFolderMembership.isRelocation(nonGraph, actual));

        EntityMessage same = new EntityMessage();
        same.folder = 11L;
        same.graph_id = "graph-id";
        assertFalse(GraphFolderMembership.isRelocation(same, actual));
        assertFalse(GraphFolderMembership.isRelocation(null, actual));
        assertFalse(GraphFolderMembership.isRelocation(same, null));
    }

    @Test
    public void removalResolutionUsesCurrentGraphIdBeforeStableIdFallback() throws Exception {
        EntityMessage cached = new EntityMessage();
        cached.graph_id = "current-id";
        cached.msgid = "<stable@example.test>";
        String currentUrl = GraphMailApi.BASE + "/me/messages/current-id?$select=id,parentFolderId,isRead,flag";
        List<String> requested = new ArrayList<>();
        GraphMailApi api = new GraphMailApi(null, "token", url -> {
            requested.add(url.toString());
            return new ZohoTestHttpsConnection(url, 200,
                    "{\"id\":\"current-id\",\"parentFolderId\":\"destination\"}");
        });

        GraphMailApi.MessageState state = GraphFolderMembership.resolveRemoval(api, cached);

        assertEquals(java.util.Arrays.asList(currentUrl), requested);
        assertEquals("current-id", state.id);
        assertEquals("destination", state.parentFolderId);
    }

    @Test
    public void movedCandidateRequiresOneSameAccountNativeGraphRow() {
        EntityMessage unique = message(7L, "old-id");
        assertNull(GraphFolderMembership.findUniqueMovedCandidate(null, 7L, "new-id"));
        assertNull(GraphFolderMembership.findUniqueMovedCandidate(
                java.util.Collections.emptyList(), 7L, "new-id"));
        assertSame(unique, GraphFolderMembership.findUniqueMovedCandidate(
                java.util.Collections.singletonList(unique), 7L, "new-id"));

        EntityMessage otherAccount = message(8L, "other-id");
        EntityMessage gmail = message(7L, "gmail-id");
        gmail.gmail_id = "gmail-native";
        EntityMessage zoho = message(7L, "zoho-id");
        zoho.zoho_id = "zoho-native";
        EntityMessage sameId = message(7L, "new-id");
        assertNull(GraphFolderMembership.findUniqueMovedCandidate(
                java.util.Arrays.asList(otherAccount, gmail, zoho, sameId), 7L, "new-id"));

        EntityMessage second = message(7L, "second-id");
        assertNull(GraphFolderMembership.findUniqueMovedCandidate(
                java.util.Arrays.asList(unique, second), 7L, "new-id"));
    }

    private static EntityMessage message(long account, String graphId) {
        EntityMessage message = new EntityMessage();
        message.account = account;
        message.graph_id = graphId;
        return message;
    }

    @Test
    public void locationChangesDeferRelocationButSeenAndFlagIntentsDoNot() {
        String[] pending = {EntityOperation.SEEN};
        DaoOperation operations = (DaoOperation) Proxy.newProxyInstance(
                DaoOperation.class.getClassLoader(), new Class<?>[]{DaoOperation.class},
                (proxy, method, args) -> {
                    if (!"getOperation".equals(method.getName()))
                        throw new AssertionError("Unexpected operation lookup");
                    assertEquals(42L, args[0]);
                    return pending[0].equals(args[1]) ? new EntityOperation() : null;
                });
        assertFalse(GraphFolderMembership.hasPendingLocationChange(operations, 42L));
        pending[0] = EntityOperation.FLAG;
        assertFalse(GraphFolderMembership.hasPendingLocationChange(operations, 42L));
        for (String name : new String[]{EntityOperation.MOVE, EntityOperation.DELETE,
                EntityOperation.GRAPH_DELETE_PERMANENT, EntityOperation.GRAPH_PERMANENT_DELETE}) {
            pending[0] = name;
            assertTrue(GraphFolderMembership.hasPendingLocationChange(operations, 42L));
        }
    }

    @Test
    public void duplicateMergeKeepsPendingWorkAndThenTheMostRecentlyOpenedRow() {
        EntityMessage original = message(7L, "old-id");
        EntityMessage duplicate = message(7L, "new-id");
        assertSame(original, GraphFolderMembership.chooseSurvivor(original, duplicate, false, false));
        duplicate.last_touched = 200L;
        assertSame(duplicate, GraphFolderMembership.chooseSurvivor(original, duplicate, false, false));
        original.last_touched = 300L;
        assertSame(original, GraphFolderMembership.chooseSurvivor(original, duplicate, false, false));
        assertSame(duplicate, GraphFolderMembership.chooseSurvivor(original, duplicate, false, true));
        assertSame(original, GraphFolderMembership.chooseSurvivor(original, duplicate, true, false));
        assertNull(GraphFolderMembership.chooseSurvivor(original, duplicate, true, true));
    }

    private static EntityFolder folder(long id, String graphId) {
        EntityFolder folder = new EntityFolder();
        folder.id = id;
        folder.graph_folder_id = graphId;
        return folder;
    }
}
