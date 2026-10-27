package eu.faircode.email;

import org.junit.Test;
import org.json.JSONObject;

import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GraphMailReceiverTest {
    @Test
    public void selectedFoldersUseBoundedBootstrapThenDeltaAndRespectSyncSelection() {
        EntityFolder drafts = new EntityFolder(EntityFolder.DRAFTS, EntityFolder.DRAFTS);
        drafts.graph_folder_id = "graph-drafts-id";

        assertEquals(10, GraphMailApi.PAGE_SIZE);
        assertTrue(GraphMailReceiver.shouldSyncSelectedFolder(drafts));
        assertTrue(GraphMailReceiver.shouldBootstrapFolder(drafts));

        drafts.graph_delta_link = "https://graph.microsoft.com/v1.0/delta/token";
        assertFalse(GraphMailReceiver.shouldBootstrapFolder(drafts));
        drafts.download = false;
        assertTrue("download=false does not gate native Graph body imports",
                GraphMailReceiver.shouldSyncAutomatically(drafts));
        drafts.synchronize = false;
        assertFalse(GraphMailReceiver.shouldSyncSelectedFolder(drafts));
        assertFalse(GraphMailReceiver.shouldSyncAutomatically(drafts));
    }

    @Test
    public void inboxAndOlderPageActionsRemainDistinct() {
        EntityFolder inbox = new EntityFolder("INBOX", EntityFolder.INBOX);
        assertTrue(GraphMailReceiver.shouldSyncAutomatically(inbox));
        inbox.synchronize = false;
        assertFalse(GraphMailReceiver.shouldSyncAutomatically(inbox));

        assertFalse(GraphMailReceiver.isOlderPageRequest("[7,30,true,false,30,false]"));
        assertTrue(GraphMailReceiver.isOlderPageRequest("[7,90,true,false,90,false,true]"));
    }

    @Test
    public void manualFetchCanBootstrapMappedFolderWithoutEnablingAutomaticSync() {
        EntityFolder folder = new EntityFolder("Projects", EntityFolder.USER);
        folder.selectable = true;
        folder.download = true;
        folder.graph_folder_id = "graph-projects";
        folder.synchronize = false;

        assertTrue(GraphMailReceiver.canFetchMoreManually(folder.selectable,
                folder.graph_folder_id, folder.graph_delta_link, folder.graph_page_link));
        assertFalse(GraphMailReceiver.shouldSyncAutomatically(folder));
        assertFalse(GraphMailReceiver.shouldSyncSelectedFolder(folder));

        folder.graph_delta_link = "https://graph.example/delta";
        assertFalse("A completed Graph bootstrap with no page link is exhausted",
                GraphMailReceiver.canFetchMoreManually(folder.selectable,
                        folder.graph_folder_id, folder.graph_delta_link, folder.graph_page_link));
    }

    @Test
    public void graphSyncMimeHasReadableBodyAndCidMetadataButNoOrdinaryAttachmentBytes() throws Exception {
        GraphMailApi.MessageDetails details = new GraphMailApi.MessageDetails(new JSONObject(
                "{\"id\":\"seed-message\",\"size\":8192," +
                        "\"body\":{\"contentType\":\"html\",\"content\":\"<p>body</p><img src='cid:logo@example.test'>\"}," +
                        "\"internetMessageHeaders\":[{\"name\":\"Subject\",\"value\":\"Graph body\"}]}"));
        List<NativeApiContent.Attachment> attachments = new ArrayList<>();
        attachments.add(new NativeApiContent.Attachment("file-id", null,
                "#microsoft.graph.fileAttachment", "report.txt", "text/plain",
                Part.ATTACHMENT, null, 4L, null, false));
        attachments.add(new NativeApiContent.Attachment("inline-id", null,
                "#microsoft.graph.fileAttachment", "logo.png", "image/png",
                Part.INLINE, "logo@example.test", 12L, null, true));

        MimeMessage mime = GraphMailReceiver.createMimeMessage(
                Session.getInstance(new Properties()), details, true, attachments);

        assertEquals("Graph body", mime.getHeader("Subject")[0]);
        Multipart mixed = (Multipart) mime.getContent();
        MimeBodyPart relatedContainer = (MimeBodyPart) mixed.getBodyPart(0);
        Multipart related = (Multipart) relatedContainer.getContent();
        assertEquals("<p>body</p><img src='cid:logo@example.test'>",
                related.getBodyPart(0).getContent());
        MimeBodyPart inline = (MimeBodyPart) related.getBodyPart(1);
        assertEquals("<logo@example.test>", inline.getHeader("Content-ID")[0]);
        assertEquals("inline-id", NativeApiContent.getInternalHeader(inline,
                NativeApiContent.HEADER_REMOTE_ID));
        MimeBodyPart ordinary = (MimeBodyPart) mixed.getBodyPart(1);
        assertEquals("file-id", NativeApiContent.getInternalHeader(ordinary,
                NativeApiContent.HEADER_REMOTE_ID));
        assertEquals("Sync keeps ordinary file payloads remote", -1, ordinary.getInputStream().read());
        assertEquals("CID render metadata is preserved without eagerly copying image bytes",
                -1, inline.getInputStream().read());
    }

    @Test
    public void bootstrapKeepsOnlyTenNewestUniqueReferencesAcrossListAndDeltaPages() {
        GraphInitialWindow initial = new GraphInitialWindow(GraphMailApi.PAGE_SIZE);
        initial.addAll(references(1, 10));
        initial.addAll(references(11, 20));

        List<GraphMailApi.MailMessage> selected = initial.newestFirst();
        assertEquals(10, selected.size());
        assertEquals("message-20", selected.get(0).id);
        assertEquals("message-11", selected.get(9).id);
    }

    @Test
    public void bootstrapWindowAccountsForConcurrentNewReferencesAndRemovals() {
        GraphInitialWindow initial = new GraphInitialWindow(GraphMailApi.PAGE_SIZE);
        initial.addAll(references(1, 10));
        List<GraphMailApi.MailMessage> delta = references(11, 12);
        delta.add(new GraphMailApi.MailMessage("message-1", 1, 1, false, false, true));
        initial.addAll(delta);

        List<GraphMailApi.MailMessage> selected = initial.newestFirst();
        assertEquals(10, selected.size());
        assertEquals("message-12", selected.get(0).id);
        assertEquals("message-3", selected.get(9).id);
    }

    @Test
    public void olderPageLinksAdvanceAcrossActionsAndStopAfterExactExhaustion() {
        EntityFolder folder = new EntityFolder("Archive", EntityFolder.ARCHIVE);
        folder.graph_delta_link = "https://graph.example/delta/token";
        folder.graph_page_link = "https://graph.example/page/1";
        int remoteRequests = 0;

        while (!GraphMailReceiver.isFolderPageExhausted(folder)) {
            String next = (remoteRequests < 12
                    ? "https://graph.example/page/" + (remoteRequests + 2)
                    : null);
            GraphMailReceiver.updateFolderPageLink(folder,
                    new GraphMailApi.Page(new ArrayList<GraphMailApi.MailMessage>(), next, null, 200));
            remoteRequests++;
        }

        assertEquals("A user action still requests at most one ten-reference page",
                10, GraphMailApi.PAGE_SIZE);
        assertEquals(13, remoteRequests);
        assertTrue(GraphMailReceiver.isFolderPageExhausted(folder));
        for (int retry = 0; retry < 3; retry++)
            assertTrue("Repeated Fetch more actions remain exhausted",
                    GraphMailReceiver.isFolderPageExhausted(folder));
        assertEquals("No remote continuation is requested after exhaustion", 13, remoteRequests);
    }

    @Test
    public void newerDeltaRefreshContinuesBeyondTenPagesUntilCheckpoint() {
        List<GraphMailApi.MailMessage> imported = new ArrayList<>();
        int requests = 0;
        GraphMailApi.Page page;
        for (int batch = 0; batch < 12; batch++) {
            page = new GraphMailApi.Page(references(batch * 10 + 1, batch * 10 + 10),
                    "https://graph.example/delta/page/" + (batch + 1), null, 200);
            assertFalse(GraphMailReceiver.hasDeltaCheckpoint(page));
            assertTrue(GraphMailReceiver.hasDeltaContinuation(page));
            imported.addAll(page.messages);
            requests++;
        }
        page = new GraphMailApi.Page(references(121, 125), null,
                "https://graph.example/delta/checkpoint", 200);
        imported.addAll(page.messages);
        requests++;

        assertTrue(GraphMailReceiver.hasDeltaCheckpoint(page));
        assertFalse(GraphMailReceiver.hasDeltaContinuation(page));
        assertEquals(13, requests);
        assertEquals(125, imported.size());
        assertTrue("A refresh may span more than the initial ten-message window",
                imported.size() > GraphMailApi.PAGE_SIZE);
    }

    private static List<GraphMailApi.MailMessage> references(int first, int last) {
        List<GraphMailApi.MailMessage> result = new ArrayList<>();
        for (int i = first; i <= last; i++)
            result.add(new GraphMailApi.MailMessage("message-" + i, i, i,
                    false, false, false));
        return result;
    }
}
