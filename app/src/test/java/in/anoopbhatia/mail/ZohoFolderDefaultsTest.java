package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ZohoFolderDefaultsTest {
    @Test
    public void resolvesUniqueTrashUsingBothNameAndServerType() {
        ZohoApi.Folder trash = new ZohoApi.Folder("100", "Trash", "Trash", true);

        assertSame(trash, ZohoFolderDefaults.findUniqueSystemFolder(
                Collections.singletonList(trash), EntityFolder.TRASH));
    }

    @Test
    public void resolvesZohoBinAliasWhenServerTypeConfirmsTrash() {
        ZohoApi.Folder bin = new ZohoApi.Folder("101", "Bin", "Trash", true);

        assertSame(bin, ZohoFolderDefaults.findUniqueSystemFolder(
                Collections.singletonList(bin), EntityFolder.TRASH));
    }

    @Test
    public void rejectsFolderNamedTrashWhenServerTypeSaysInbox() {
        ZohoApi.Folder custom = new ZohoApi.Folder("102", "Trash", "Inbox", true);

        assertNull(ZohoFolderDefaults.findUniqueSystemFolder(
                Collections.singletonList(custom), EntityFolder.TRASH));
    }

    @Test
    public void rejectsDuplicateTrashCandidates() {
        ZohoApi.Folder trash = new ZohoApi.Folder("100", "Trash", "Trash", true);
        ZohoApi.Folder bin = new ZohoApi.Folder("101", "Bin", "Trash", true);

        assertNull(ZohoFolderDefaults.findUniqueSystemFolder(
                Arrays.asList(trash, bin), EntityFolder.TRASH));
    }

    @Test
    public void rejectsUnselectableOrInvalidRemoteFolder() {
        ZohoApi.Folder unselectable = new ZohoApi.Folder("100", "Trash", "Trash", false);
        ZohoApi.Folder invalidId = new ZohoApi.Folder("not-numeric", "Trash", "Trash", true);

        assertNull(ZohoFolderDefaults.findUniqueSystemFolder(
                Collections.singletonList(unselectable), EntityFolder.TRASH));
        assertNull(ZohoFolderDefaults.findUniqueSystemFolder(
                Collections.singletonList(invalidId), EntityFolder.TRASH));
    }
}
