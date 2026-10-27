package eu.faircode.email;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccountListPresentationTest {
    @Test
    public void nativeRestRowsKeepTimestampButHideProviderAndDiagnosticSuffixes() {
        EntityAccount gmail = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, "imap.gmail.com", "google");
        EntityAccount graph = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API,
                "outlook.office365.com", "outlookgraph");
        EntityAccount zoho = account(ServiceAuthenticator.AUTH_TYPE_ZOHO, "imap.zoho.com", "zoho_mail_api");

        for (EntityAccount account : Arrays.asList(gmail, graph, zoho)) {
            account.last_connected = 1791037200000L;
            account.last_modified = 1791037100000L;
            account.poll_interval = 15;
            account.keep_alive_ok = false;
            account.keep_alive_failed = 0;
            account.keep_alive_succeeded = 0;
            String provider = account.provider;

            assertEquals("", AccountListPresentation.getProviderSuffix(account, true));
            assertEquals("Sat, 03 Oct 2026 16:40:00", AccountListPresentation.formatLastConnected(
                    account, "Sat, 03 Oct 2026 16:40:00", "Sat, 03 Oct 2026 16:38:20", true));
            assertFalse(AccountListPresentation.showDebugId(true, true, account));

            assertEquals("API provider metadata must remain untouched", provider, account.provider);
            assertEquals(Long.valueOf(1791037200000L), account.last_connected);
            assertEquals(Long.valueOf(1791037100000L), account.last_modified);
            assertEquals(Integer.valueOf(15), account.poll_interval);
            assertEquals(Boolean.FALSE, account.keep_alive_ok);
            assertEquals(Integer.valueOf(0), account.keep_alive_failed);
            assertEquals(Integer.valueOf(0), account.keep_alive_succeeded);
        }
    }

    @Test
    public void legacyRowsRetainExistingDebugDetailsAndProviderSuffix() {
        EntityAccount legacy = account(ServiceAuthenticator.AUTH_TYPE_PASSWORD,
                "imap.example.org", "legacy-provider");
        legacy.poll_interval = 15;
        legacy.keep_alive_ok = false;
        legacy.keep_alive_failed = 0;
        legacy.keep_alive_succeeded = 0;

        assertEquals(" (legacy-provider)", AccountListPresentation.getProviderSuffix(legacy, true));
        assertEquals("2026-10-03 16:40:00/2026-10-03 16:38:20 15/false/0/0",
                AccountListPresentation.formatLastConnected(legacy,
                        "2026-10-03 16:40:00", "2026-10-03 16:38:20", true));
        assertTrue(AccountListPresentation.showDebugId(true, true, legacy));
    }

    @Test
    public void nonDebugAndNonSettingsRowsKeepTheirExistingVisibilityRules() {
        EntityAccount legacy = account(ServiceAuthenticator.AUTH_TYPE_PASSWORD,
                "imap.example.org", "legacy-provider");

        assertEquals("", AccountListPresentation.getProviderSuffix(legacy, false));
        assertEquals("2026-10-03 16:40:00", AccountListPresentation.formatLastConnected(legacy,
                "2026-10-03 16:40:00", "2026-10-03 16:38:20", false));
        assertFalse(AccountListPresentation.showDebugId(false, true, legacy));
        assertFalse(AccountListPresentation.showDebugId(true, false, legacy));
    }

    private static EntityAccount account(int authType, String host, String provider) {
        EntityAccount account = new EntityAccount();
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = authType;
        account.host = host;
        account.provider = provider;
        return account;
    }
}
