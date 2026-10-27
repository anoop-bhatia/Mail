package in.anoopbhatia.mail;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThreadAutoClosePolicyTest {
    @Test
    public void passiveEmptyThreadUpdateDoesNotAutoClose() {
        boolean hasUserAction = ThreadAutoClosePolicy.hasUserAction(false, false);

        assertFalse(ThreadAutoClosePolicy.shouldCloseEmptyThread(true, false, hasUserAction));
        assertFalse(ThreadAutoClosePolicy.shouldCloseEmptyThread(false, true, hasUserAction));
    }

    @Test
    public void explicitRemovalOrMovePreservesConfiguredEmptyThreadClose() {
        assertTrue(ThreadAutoClosePolicy.shouldCloseEmptyThread(true, false,
                ThreadAutoClosePolicy.hasUserAction(false, true)));
        assertTrue(ThreadAutoClosePolicy.shouldCloseEmptyThread(false, true,
                ThreadAutoClosePolicy.hasUserAction(true, false)));
        assertFalse(ThreadAutoClosePolicy.shouldCloseEmptyThread(false, false,
                ThreadAutoClosePolicy.hasUserAction(true, false)));
    }

    @Test
    public void passiveUpdatesCannotTriggerFolderActionClose() {
        assertFalse(ThreadAutoClosePolicy.shouldEvaluateFolderActionClose(true, false,
                false, 1));
        assertTrue(ThreadAutoClosePolicy.shouldEvaluateFolderActionClose(true, false,
                true, 1));
        assertFalse(ThreadAutoClosePolicy.shouldEvaluateFolderActionClose(true, false,
                true, 0));
    }
}
