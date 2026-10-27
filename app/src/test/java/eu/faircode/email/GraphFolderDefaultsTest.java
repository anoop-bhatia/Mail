package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GraphFolderDefaultsTest {
    @Test
    public void wellKnownMicrosoftFolderNamesMapToFairEmailSystemTypes() {
        assertEquals(EntityFolder.INBOX, GraphFolderDefaults.getFolderType("Inbox"));
        assertEquals(EntityFolder.DRAFTS, GraphFolderDefaults.getFolderType("Drafts"));
        assertEquals(EntityFolder.SENT, GraphFolderDefaults.getFolderType("Sent Items"));
        assertEquals(EntityFolder.TRASH, GraphFolderDefaults.getFolderType("Deleted Items"));
        assertEquals(EntityFolder.JUNK, GraphFolderDefaults.getFolderType("Junk Email"));
        assertEquals(EntityFolder.ARCHIVE, GraphFolderDefaults.getFolderType("Archive"));
    }

    @Test
    public void localizedAliasesAreCaseInsensitiveAndUnknownFoldersRemainUserFolders() {
        assertEquals(EntityFolder.INBOX, GraphFolderDefaults.getFolderType("POSTEINGANG"));
        assertEquals(EntityFolder.TRASH, GraphFolderDefaults.getFolderType("Papierkorb"));
        assertEquals(EntityFolder.JUNK, GraphFolderDefaults.getFolderType("Courrier indésirable"));
        assertEquals(EntityFolder.USER, GraphFolderDefaults.getFolderType("Quarterly Reports"));
    }
}
