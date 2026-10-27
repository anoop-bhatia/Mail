package in.anoopbhatia.mail;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OAuthAuthorizationFormStateTest {
    private static final int[] OAUTH_COMMON_CONTROLS = {
            R.id.etName, R.id.etEmail, R.id.etTenant,
            R.id.cbPop, R.id.cbRecent, R.id.cbUpdate
    };

    @Test
    public void zohoApiUsesSharedAccountControlsWithoutEditableCredentials() {
        assertArrayEquals(OAUTH_COMMON_CONTROLS,
                FragmentOAuth.getAuthorizationControlIds(ZohoOAuth.PROVIDER_ID, false));
    }

    @Test
    public void graphApiDisablesEverySharedParameterControlWithoutZohoFields() {
        assertArrayEquals(OAUTH_COMMON_CONTROLS,
                FragmentOAuth.getAuthorizationControlIds("outlookgraph", true));
    }

    @Test
    public void standardOAuthControlSetRemainsUnchanged() {
        assertArrayEquals(OAUTH_COMMON_CONTROLS,
                FragmentOAuth.getAuthorizationControlIds("microsoft", false));
    }

    @Test
    public void preservesIncomingOnlyForExistingAccountsButDefaultsNewAccountsToSending() {
        assertTrue(FragmentOAuth.shouldPreserveInboundOnly(true, true, false));
        assertFalse(FragmentOAuth.shouldPreserveInboundOnly(true, true, true));
        assertFalse(FragmentOAuth.shouldPreserveInboundOnly(true, false, false));
        assertFalse(FragmentOAuth.shouldPreserveInboundOnly(false, false, false));
    }

    @Test
    public void gmailApiBusyStateCoversAllFormInputsAndActions() {
        assertArrayEquals(new int[]{
                        R.id.etName, R.id.cbUpdate, R.id.btnSelect
                },
                FragmentGmailApi.getBusyControlIds());
    }
}
