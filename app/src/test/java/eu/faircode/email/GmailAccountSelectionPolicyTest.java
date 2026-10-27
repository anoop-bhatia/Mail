package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailAccountSelectionPolicyTest {
    @Test
    public void interactiveAuthorizationAlwaysShowsGoogleAccountChooser() {
        assertTrue(GmailAccountSelectionPolicy.alwaysPromptForAccount());
        assertArrayEquals(new String[]{"com.google"},
                GmailAccountSelectionPolicy.getAllowedAccountTypes());
    }

    @Test
    public void tapUsesNativeQuickSetupAndOnlyLongPressUsesBrowserOAuth() {
        assertFalse(GmailAccountSelectionPolicy.shouldUseBrowserOAuth(false));
        assertTrue(GmailAccountSelectionPolicy.shouldUseBrowserOAuth(true));
    }

    @Test
    public void emptyQuickSetupResultHasNoSelectedAccount() {
        assertTrue(GmailAccountSelectionPolicy.hasSelectedAccount(" chosen@example.com "));
        assertFalse(GmailAccountSelectionPolicy.hasSelectedAccount(null));
        assertFalse(GmailAccountSelectionPolicy.hasSelectedAccount("  "));
    }

    @Test
    public void selectedAccountIsTheOnlyAccountPassedToAuthorization() {
        assertEquals("chosen@example.com",
                GmailAccountSelectionPolicy.requireSelectedAccount(" chosen@example.com "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingSelectionCannotFallBackToDefaultAccount() {
        GmailAccountSelectionPolicy.requireSelectedAccount(null);
    }
}
