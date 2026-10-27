package in.anoopbhatia.mail;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Integration coverage for the SQL membership boundary and move reconciliation. */
public class GraphMembershipStorageTest {
    @Test
    public void membershipScanIsBoundedOrderedPaginatedAndGraphOnly() throws Exception {
        try (Connection db = database()) {
            insert(db, 1, 7, 10, "g-1", "body-1");
            insert(db, 2, 7, 10, "g-2", "body-2");
            insert(db, 3, 7, 10, "g-3", "body-3");
            insert(db, 4, 7, 10, null, "imap");
            insert(db, 5, 8, 10, "other-account", "wrong-account");
            insert(db, 6, 7, 11, "other-folder", "wrong-folder");
            insert(db, 7, 7, 10, "g-7", "body-7");

            List<Long> first = scan(db, 7, 10, 0, 2);
            List<Long> second = scan(db, 7, 10, first.get(first.size() - 1), 2);
            assertEquals(Arrays.asList(1L, 2L), first);
            assertEquals(Arrays.asList(3L, 7L), second);
        }
    }

    @Test
    public void deleteRequiresExactMembershipAndNoPendingOperation() throws Exception {
        try (Connection db = database()) {
            insert(db, 1, 7, 10, "g-1", "body");
            insert(db, 2, 7, 10, "g-2", "body");
            insert(db, 3, 7, 10, "g-3", "body");
            insert(db, 4, 7, 10, "g-4", "body");
            assertEquals(0, delete(db, 1, 99, "g-1")); // old folder changed
            assertEquals(0, delete(db, 2, 10, "new-id")); // Graph id changed

            for (String operation : new String[]{EntityOperation.SEEN, EntityOperation.MOVE,
                    EntityOperation.DELETE, "anything"}) {
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO operation (message, name) VALUES (?, ?)")) {
                    ps.setLong(1, 3);
                    ps.setString(2, operation);
                    ps.executeUpdate();
                }
                assertEquals("queued " + operation + " must protect the row", 0,
                        delete(db, 3, 10, "g-3"));
                try (Statement s = db.createStatement()) { s.executeUpdate("DELETE FROM operation"); }
            }
            assertEquals(1, delete(db, 4, 10, "g-4"));
            assertEquals(0, count(db, 4));
        }
    }

    @Test
    public void sourceTombstoneFirstCannotDeleteRowRelocatedToDestination() throws Exception {
        assertMovePermutation(true);
    }

    @Test
    public void destinationLiveFirstRelocatesSameRowBeforeSourceTombstone() throws Exception {
        assertMovePermutation(false);
    }

    @Test
    public void changedGraphId404UsesUniqueStableIdAndRelocatesPreservingRowIdentity() throws Exception {
        try (Connection db = database()) {
            insert(db, 41, 7, 10, "old-id", "cached body");
            try (PreparedStatement ps = db.prepareStatement(
                    "UPDATE message SET thread = ?, ui_seen = ? WHERE id = ?")) {
                ps.setString(1, "thread-keep"); ps.setInt(2, 1); ps.setLong(3, 41); ps.executeUpdate();
            }
            EntityMessage cached = row(db, 41);
            EntityFolder destination = folder(20, "destination");
            GraphMailApi api = resolvingApi("old-id", "new-id", "destination", false);
            GraphMailApi.MessageState state = GraphFolderMembership.resolveRemoval(api, cached);
            assertEquals("new-id", state.id);
            EntityMessage candidate = GraphFolderMembership.findUniqueMovedCandidate(
                    Arrays.asList(cached), 7, "new-id");
            assertSame(cached, candidate);
            DaoMessage dao = folderUpdatingDao(db);
            assertTrue(GraphFolderMembership.relocate(dao, cached, destination));
            assertEquals(Long.valueOf(41), row(db, 41).id);
            assertEquals(Long.valueOf(20), row(db, 41).folder);
            assertEquals("cached body", row(db, 41).extra);
            assertEquals("thread-keep", row(db, 41).thread);
            assertTrue(row(db, 41).ui_seen);
        }
    }

    @Test
    public void changedGraphIdDuplicateStableIdDoesNotChooseArbitraryCopy() throws Exception {
        EntityMessage cached = new EntityMessage();
        cached.account = 7L; cached.graph_id = "old-id"; cached.msgid = "<stable@example.test>";
        GraphMailApi api = resolvingApi("old-id", "new-id", "destination", true);
        assertNull(GraphFolderMembership.resolveRemoval(api, cached));
        assertNull(GraphFolderMembership.findUniqueMovedCandidate(
                Arrays.asList(message(7, "copy-1"), message(7, "copy-2")), 7, "new-id"));
    }

    private static void assertMovePermutation(boolean sourceTombstoneFirst) throws Exception {
        try (Connection db = database()) {
            insert(db, 50, 7, 10, "same-id", "body");
            EntityMessage cached = row(db, 50);
            EntityFolder source = folder(10, "source");
            EntityFolder destination = folder(20, "destination");
            GraphMailApi api = resolvingApi("same-id", "same-id", "destination", false);
            if (sourceTombstoneFirst) {
                assertTrue(GraphMailReceiver.shouldDeleteCachedMessage(cached, source));
                GraphMailApi.MessageState state = GraphFolderMembership.resolveRemoval(api, cached);
                EntityFolder actual = GraphFolderMembership.resolveFolder(folderDao(destination), 7, source, state);
                assertTrue(GraphFolderMembership.relocate(folderUpdatingDao(db), cached, actual));
            } else {
                GraphMailApi.MessageState state = GraphFolderMembership.resolveRemoval(api, cached);
                EntityFolder actual = GraphFolderMembership.resolveFolder(folderDao(destination), 7, source, state);
                assertTrue(GraphFolderMembership.relocate(folderUpdatingDao(db), cached, actual));
                assertTrue(GraphMailReceiver.shouldDeleteCachedMessage(cached, destination));
            }
            assertEquals(20L, row(db, 50).folder.longValue());
            assertEquals(1, count(db, 50));
            assertEquals("A source tombstone must not delete the relocated row", 0,
                    delete(db, 50, 10, "same-id"));
            assertFalse(GraphMailReceiver.shouldDeleteCachedMessage(cached, source));
        }
    }

    private static Connection database() throws Exception {
        Connection db = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE message (id INTEGER PRIMARY KEY, account INTEGER, folder INTEGER, graph_id TEXT, msgid TEXT, extra TEXT, thread TEXT, ui_seen INTEGER)");
            s.execute("CREATE TABLE operation (message INTEGER, name TEXT)");
        }
        return db;
    }

    private static void insert(Connection db, long id, long account, long folder, String graph, String extra) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO message VALUES (?, ?, ?, ?, ?, ?, ?, 0)")) {
            ps.setLong(1, id); ps.setLong(2, account); ps.setLong(3, folder); ps.setString(4, graph);
            ps.setString(5, "<" + id + "@example.test>"); ps.setString(6, extra); ps.setString(7, "thread-" + id); ps.executeUpdate();
        }
    }

    private static List<Long> scan(Connection db, long account, long folder, long after, int limit) throws Exception {
        String sql = DaoMessage.GRAPH_MEMBERSHIP_SCAN.replace(":account", "?").replace(":folder", "?")
                .replace(":afterId", "?").replace(":limit", "?");
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setLong(1, account); ps.setLong(2, folder); ps.setLong(3, after); ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getLong("id")); }
        }
        return ids;
    }

    private static int delete(Connection db, long id, long folder, String graph) throws Exception {
        String sql = DaoMessage.GRAPH_MEMBERSHIP_DELETE.replace(":id", "?").replace(":folder", "?").replace(":graphId", "?");
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setLong(1, id); ps.setLong(2, folder); ps.setString(3, graph); return ps.executeUpdate();
        }
    }

    private static int count(Connection db, long id) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("SELECT count(*) FROM message WHERE id=?")) {
            ps.setLong(1, id); try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static EntityMessage row(Connection db, long id) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("SELECT * FROM message WHERE id=?")) {
            ps.setLong(1, id); try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) return null;
                EntityMessage m = new EntityMessage(); m.id = rs.getLong("id"); m.account = rs.getLong("account");
                m.folder = rs.getLong("folder"); m.graph_id = rs.getString("graph_id"); m.msgid = rs.getString("msgid");
                m.extra = rs.getString("extra"); m.thread = rs.getString("thread"); m.ui_seen = rs.getInt("ui_seen") != 0; return m;
            }
        }
    }

    private static DaoMessage folderUpdatingDao(Connection db) {
        return (DaoMessage) Proxy.newProxyInstance(DaoMessage.class.getClassLoader(), new Class<?>[]{DaoMessage.class},
                (proxy, method, args) -> {
                    if ("setMessageFolder".equals(method.getName())) {
                        try (PreparedStatement ps = db.prepareStatement("UPDATE message SET folder=? WHERE id=?")) {
                            ps.setLong(1, (Long) args[1]); ps.setLong(2, (Long) args[0]); return ps.executeUpdate();
                        }
                    }
                    return null;
                });
    }

    private static DaoFolder folderDao(EntityFolder destination) {
        return (DaoFolder) Proxy.newProxyInstance(DaoFolder.class.getClassLoader(), new Class<?>[]{DaoFolder.class},
                (proxy, method, args) -> "getFolderByGraphId".equals(method.getName()) ? destination : null);
    }

    private static EntityFolder folder(long id, String graphId) { EntityFolder f = new EntityFolder(); f.id = id; f.graph_folder_id = graphId; return f; }
    private static EntityMessage message(long account, String graph) { EntityMessage m = new EntityMessage(); m.account = account; m.graph_id = graph; return m; }

    private static GraphMailApi resolvingApi(String oldId, String newId, String parent, boolean duplicate) {
        return new GraphMailApi(null, "token", url -> {
            String text = url.toString();
            if (oldId.equals(newId) && text.contains("/messages/" + oldId + "?"))
                return new ZohoTestHttpsConnection(url, 200,
                        "{\"id\":\"" + newId + "\",\"parentFolderId\":\"" + parent + "\"}");
            if (text.contains("/messages/" + oldId + "?")) return new ZohoTestHttpsConnection(url, 404, "{\"error\":{\"code\":\"ErrorItemNotFound\"}}");
            if (duplicate) return new ZohoTestHttpsConnection(url, 200, "{\"value\":[{\"id\":\"copy-1\"},{\"id\":\"copy-2\"}]}");
            return new ZohoTestHttpsConnection(url, 200, "{\"value\":[{\"id\":\"" + newId + "\",\"parentFolderId\":\"" + parent + "\"}]}");
        });
    }
}
