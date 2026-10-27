package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class OAuthAuthorizationFormStateTest {
    private static final int[] OAUTH_COMMON_CONTROLS = {
            R.id.etName, R.id.etEmail, R.id.etTenant,
            R.id.cbInboundOnly, R.id.cbPop, R.id.cbRecent, R.id.cbUpdate
    };

    @Test
    public void zohoApiDisablesClientCredentialsAndAllSharedOptions() {
        assertArrayEquals(new int[]{
                        R.id.etName, R.id.etEmail, R.id.etTenant,
                        R.id.cbInboundOnly, R.id.cbPop, R.id.cbRecent, R.id.cbUpdate,
                        R.id.etZohoClientId, R.id.etZohoClientSecret, R.id.etZohoRedirectUri
                },
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
    public void gmailApiBusyStateCoversAllFormInputsAndActions() {
        assertArrayEquals(new int[]{
                        R.id.etName, R.id.cbUpdate, R.id.btnSelect
                },
                FragmentGmailApi.getBusyControlIds());
    }
}
