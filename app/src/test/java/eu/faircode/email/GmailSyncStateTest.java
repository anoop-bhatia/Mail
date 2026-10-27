package eu.faircode.email;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailSyncStateTest {
    @Test
    public void secondSyncAfterInitialCompletionUsesHistoryInsteadOfRepeatingMessageWindow() {
        GmailSyncState state = GmailSyncState.empty();
        assertTrue(state.needsProfile());
        assertEquals(GmailSyncState.Stage.FULL, state.getStage());

        state = state.beginFullSync("history-100");
        state = state.afterFullPage(null);

        assertEquals("history-100", state.historyId);
        assertFalse(state.isFullSync());
        assertFalse(state.hasMoreWork());
        assertEquals(GmailSyncState.Stage.HISTORY, state.getStage());
        assertFalse("A completed bootstrap must not list the same first 10 messages again",
                state.needsProfile());
    }

    @Test
    public void fullSyncPageTokenIsPersistableAndCompletesWithoutRepeatingEarlierPages() {
        GmailSyncState firstPass = GmailSyncState.empty().beginFullSync("history-start")
                .afterFullPage("opaque/page+token");

        // Simulate process death/reopen: reconstruct the state solely from persisted cursor columns.
        GmailSyncState resumed = new GmailSyncState(firstPass.historyId,
                firstPass.fullSyncHistoryId, firstPass.fullSyncPageToken,
                firstPass.historyPageToken, firstPass.historyPendingJson);
        assertEquals(GmailSyncState.Stage.FULL, resumed.getStage());
        assertEquals("opaque/page+token", resumed.fullSyncPageToken);
        assertTrue(resumed.hasMoreWork());

        GmailSyncState completed = resumed.afterFullPage(null);
        assertEquals("history-start", completed.historyId);
        assertFalse(completed.isFullSync());
        assertFalse(completed.hasMoreWork());
        assertEquals(GmailSyncState.Stage.HISTORY, completed.getStage());
    }

    @Test
    public void historyPagesResumeFromSavedTokenAndCommitLatestHistoryOnlyAtTheEnd() {
        GmailSyncState first = new GmailSyncState("history-10", null, null, null, null)
                .afterHistoryPage("history-page-2", "history-20");
        assertEquals("history-10", first.historyId);
        assertEquals("history-page-2", first.historyPageToken);
        assertTrue(first.hasMoreWork());

        GmailSyncState resumed = new GmailSyncState(first.historyId, first.fullSyncHistoryId,
                first.fullSyncPageToken, first.historyPageToken, first.historyPendingJson);
        GmailSyncState completed = resumed.afterHistoryPage(null, "history-30");
        assertEquals("history-30", completed.historyId);
        assertEquals(null, completed.historyPageToken);
        assertFalse(completed.hasMoreWork());
    }

    @Test
    public void expiredHistoryCursorClearsAllPartialStateForControlledResync() {
        GmailSyncState expired = GmailSyncState.expiredHistory();
        assertTrue(expired.needsProfile());
        assertEquals(GmailSyncState.Stage.FULL, expired.getStage());
        assertEquals(null, expired.historyPageToken);
        assertEquals(null, expired.fullSyncPageToken);
    }

    @Test
    public void historyChangesResumeInBoundedChunksWithoutAdvancingTheHistoryCursor() throws Exception {
        GmailSyncState initial = new GmailSyncState("history-10", null, null, null, null)
                .withHistoryPending("{\"changes\":[{\"id\":\"message-2\"}]}");

        GmailSyncState resumed = new GmailSyncState(initial.historyId,
                initial.fullSyncHistoryId, initial.fullSyncPageToken,
                initial.historyPageToken, initial.historyPendingJson);
        assertEquals("history-10", resumed.historyId);
        assertEquals("message-2", GmailApi.HistoryPage.fromPendingJson(
                resumed.historyPendingJson).changes.get(0).id);
        assertTrue(resumed.hasMoreWork());

        GmailSyncState completed = resumed.afterHistoryPage(null, "history-20");
        assertEquals("history-20", completed.historyId);
        assertEquals(null, completed.historyPendingJson);
        assertFalse(completed.hasMoreWork());
    }

    @Test
    public void historyRefreshCanContinueBeyondTenPagesBeforeCommittingItsCheckpoint() {
        GmailSyncState state = new GmailSyncState("history-start", null, null, null, null);
        for (int page = 1; page <= 12; page++) {
            state = state.afterHistoryPage("history-page-" + (page + 1),
                    "history-through-" + page);
            assertEquals("The checkpoint stays fixed until the final continuation page",
                    "history-start", state.historyId);
            assertTrue(state.hasMoreWork());
        }

        state = state.afterHistoryPage(null, "history-final");
        assertEquals("history-final", state.historyId);
        assertEquals(null, state.historyPageToken);
        assertFalse(state.hasMoreWork());
    }

    @Test
    public void currentLabelsRecomputeUnreadStarImportanceAndVirtualLabelMembership() {
        GmailMessageLabelState before = GmailMessageLabelState.from(
                Arrays.asList("INBOX", "UNREAD", "STARRED", "IMPORTANT", "Label_42"));
        assertFalse(before.seen);
        assertTrue(before.flagged);
        assertEquals(Integer.valueOf(EntityMessage.PRIORITIY_HIGH), before.importance);
        assertTrue(GmailLabelFolder.isMember(before.labels.toArray(new String[0]), "Label_42"));

        GmailMessageLabelState after = GmailMessageLabelState.from(
                Arrays.asList("INBOX", "Label_42"));
        assertTrue(after.seen);
        assertFalse(after.flagged);
        assertEquals(null, after.importance);
        assertTrue(GmailLabelFolder.isMember(after.labels.toArray(new String[0]), "Label_42"));
        assertFalse(GmailLabelFolder.isMember(after.labels.toArray(new String[0]), "STARRED"));

        GmailMessageLabelState noLabels = GmailMessageLabelState.from(Collections.<String>emptyList());
        assertTrue(noLabels.seen);
        assertFalse(noLabels.flagged);
        assertFalse(noLabels.deleted);
    }
}
