package eu.faircode.email;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccountEditPolicyTest {
    @Test
    public void nativeRestProvidersUseProtectedApiEditorPolicy() {
        EntityAccount gmail = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, "imap.gmail.com");
        EntityAccount zoho = account(ServiceAuthenticator.AUTH_TYPE_ZOHO, "imap.zoho.com");
        EntityAccount graph = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API, "outlook.office365.com");

        for (EntityAccount account : Arrays.asList(gmail, zoho, graph)) {
            assertTrue(AccountEditPolicy.isNativeRestApi(account));
            assertFalse(AccountEditPolicy.shouldSaveTransportSettings(account));
            assertTrue(AccountEditPolicy.shouldShowSpecialFolderMappings(account));
            assertTrue(AccountEditPolicy.shouldShowNativeRestServerGroup(account));
            assertTrue(AccountEditPolicy.shouldShowNativeRestCalendarGroup(account, false));
            assertFalse(AccountEditPolicy.shouldShowNativeRestCalendarGroup(account, true));
            assertTrue(AccountEditPolicy.shouldShowNativeRestAdvancedButton(account));
            assertFalse(AccountEditPolicy.shouldEnableProviderSelection(account));
            assertEquals(R.string.title_related_identity_api,
                    AccountEditPolicy.getRelatedIdentityLabelResourceId(account, 0));
        }
    }

    @Test
    public void legacyGmailHostImapAndOrdinaryImapRemainOnLegacyPolicy() {
        EntityAccount gmailImap = account(ServiceAuthenticator.AUTH_TYPE_PASSWORD, "imap.gmail.com");
        EntityAccount ordinaryImap = account(ServiceAuthenticator.AUTH_TYPE_PASSWORD, "imap.example.org");

        for (EntityAccount account : Arrays.asList(gmailImap, ordinaryImap)) {
            assertFalse(AccountEditPolicy.isNativeRestApi(account));
            assertTrue(AccountEditPolicy.shouldSaveTransportSettings(account));
            assertTrue(AccountEditPolicy.shouldShowSpecialFolderMappings(account));
            assertFalse(AccountEditPolicy.shouldShowNativeRestServerGroup(account));
            assertFalse(AccountEditPolicy.shouldShowNativeRestCalendarGroup(account, false));
            assertFalse(AccountEditPolicy.shouldShowNativeRestCalendarGroup(account, true));
            assertFalse(AccountEditPolicy.shouldShowNativeRestAdvancedButton(account));
            assertTrue(AccountEditPolicy.shouldEnableProviderSelection(account));
            assertEquals(R.string.title_related_identity,
                    AccountEditPolicy.getRelatedIdentityLabelResourceId(account, 2));
        }
    }

    @Test
    public void nativeRestProviderSelectionUsesProviderIdsNotSharedImapHosts() {
        EntityAccount gmail = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, "imap.gmail.com");
        EntityAccount zoho = account(ServiceAuthenticator.AUTH_TYPE_ZOHO, "imap.zoho.com");
        zoho.provider = ZohoOAuth.PROVIDER_ID;
        EntityAccount graph = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API, "outlook.office365.com");
        graph.provider = GraphOAuth.PROVIDER_ID;

        List<EmailProvider> providers = new ArrayList<>(Arrays.asList(
                provider("google-imap", "imap.gmail.com"),
                provider("zoho", "imap.zoho.com"),
                provider("office365", "outlook.office365.com"),
                provider(GraphOAuth.LEGACY_PROVIDER_ID, "outlook.office365.com"),
                provider(ZohoOAuth.PROVIDER_ID, "imap.zoho.com"),
                provider(GmailApi.PROVIDER_ID, "imap.gmail.com"),
                provider(GraphOAuth.PROVIDER_ID, "graph.microsoft.com")));

        assertEquals("gmailapi", AccountEditPolicy.getNativeRestProviderId(gmail));
        assertEquals("zohomailapi", AccountEditPolicy.getNativeRestProviderId(zoho));
        assertEquals("msgraphapi", AccountEditPolicy.getNativeRestProviderId(graph));
        assertEquals(5, AccountEditPolicy.findNativeRestProviderPosition(providers, gmail));
        assertEquals(4, AccountEditPolicy.findNativeRestProviderPosition(providers, zoho));
        assertEquals(6, AccountEditPolicy.findNativeRestProviderPosition(providers, graph));
        assertEquals("outlookgraph", GraphOAuth.LEGACY_PROVIDER_ID);
        assertEquals(-1, AccountEditPolicy.findNativeRestProviderPosition(providers,
                account(ServiceAuthenticator.AUTH_TYPE_PASSWORD, "imap.gmail.com")));
    }

    @Test
    public void nativeRestIdentityActionEditsExistingAndPromptsWhenAmbiguous() {
        EntityAccount rest = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, "imap.gmail.com");
        EntityAccount legacy = account(ServiceAuthenticator.AUTH_TYPE_PASSWORD, "imap.gmail.com");

        assertEquals(R.string.title_related_identity_api,
                AccountEditPolicy.getRelatedIdentityLabelResourceId(rest, 0));
        assertEquals(R.string.title_edit_related_identity,
                AccountEditPolicy.getRelatedIdentityLabelResourceId(rest, 1));
        assertTrue(AccountEditPolicy.shouldEditRelatedIdentity(rest, 1));
        assertFalse(AccountEditPolicy.shouldShowRelatedIdentityPicker(rest, 1));
        assertTrue(AccountEditPolicy.shouldShowRelatedIdentityPicker(rest, 2));
        assertFalse(AccountEditPolicy.shouldEditRelatedIdentity(legacy, 2));

        EntityIdentity one = new EntityIdentity();
        one.id = 41L;
        assertEquals(Long.valueOf(41L), AccountEditPolicy.getSoleRelatedIdentityId(Arrays.asList(one)));
        EntityIdentity two = new EntityIdentity();
        two.id = 42L;
        assertNull(AccountEditPolicy.getSoleRelatedIdentityId(Arrays.asList(one, two)));
        assertNull(AccountEditPolicy.getSoleRelatedIdentityId(null));
    }

    @Test
    public void hiddenTransportFieldsExcludeTheReadOnlyAccountAddress() {
        Set<Integer> hidden = asSet(AccountEditPolicy.getNativeRestTransportViewIds());

        assertTrue(hidden.contains(R.id.tvImap));
        assertTrue(hidden.contains(R.id.cbDnsSec));
        assertTrue(hidden.contains(R.id.tvDomain));
        assertTrue(hidden.contains(R.id.tvDomainHint));
        assertTrue(hidden.contains(R.id.etDomain));
        assertTrue(hidden.contains(R.id.btnAutoConfig));
        assertTrue(hidden.contains(R.id.pbAutoConfig));
        assertTrue(hidden.contains(R.id.tvHost));
        assertTrue(hidden.contains(R.id.etHost));
        assertTrue(hidden.contains(R.id.rgEncryption));
        assertTrue(hidden.contains(R.id.cbInsecure));
        assertTrue(hidden.contains(R.id.cbDane));
        assertTrue(hidden.contains(R.id.tvPort));
        assertTrue(hidden.contains(R.id.etPort));
        assertFalse(hidden.contains(R.id.tvUser));
        assertFalse(hidden.contains(R.id.etUser));
        assertFalse(hidden.contains(R.id.etName));
    }

    @Test
    public void providerSelectionRehidesNativeRestGroupChildrenWithoutHidingMetadata() {
        EntityAccount gmail = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, "imap.gmail.com");
        EntityAccount zoho = account(ServiceAuthenticator.AUTH_TYPE_ZOHO, "imap.zoho.com");
        EntityAccount graph = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API, "outlook.office365.com");

        for (EntityAccount account : Arrays.asList(gmail, zoho, graph)) {
            Set<Integer> hidden = new HashSet<>();
            AccountEditPolicy.hideNativeRestProviderSelectionViews(account, 1, hidden::add);
            assertEquals(asSet(AccountEditPolicy.getNativeRestProviderSelectionHiddenViewIds(account, 1)),
                    hidden);
            assertTrue(hidden.contains(R.id.tvDomain));
            assertTrue(hidden.contains(R.id.tvDomainHint));
            assertTrue(hidden.contains(R.id.etDomain));
            assertTrue(hidden.contains(R.id.btnAutoConfig));
            assertTrue(hidden.contains(R.id.btnCertificate));
            assertTrue(hidden.contains(R.id.tvCertificate));
            assertTrue(hidden.contains(R.id.tvRealm));
            assertTrue(hidden.contains(R.id.etRealm));
            assertTrue(hidden.contains(R.id.tvHost));
            assertTrue(hidden.contains(R.id.etPort));
            assertFalse(hidden.contains(R.id.tvName));
            assertFalse(hidden.contains(R.id.etName));
            assertFalse(hidden.contains(R.id.tvCategory));
            assertFalse(hidden.contains(R.id.etCategory));
            assertFalse(hidden.contains(R.id.tvUser));
            assertFalse(hidden.contains(R.id.etUser));
        }

        assertEquals(0, AccountEditPolicy.getNativeRestProviderSelectionHiddenViewIds(gmail, 0).length);
        Set<Integer> hiddenAtPlaceholder = new HashSet<>();
        AccountEditPolicy.hideNativeRestProviderSelectionViews(gmail, 0, hiddenAtPlaceholder::add);
        assertTrue(hiddenAtPlaceholder.isEmpty());

        assertEquals(0, AccountEditPolicy.getNativeRestProviderSelectionHiddenViewIds(
                account(ServiceAuthenticator.AUTH_TYPE_PASSWORD, "imap.example.org"), 1).length);
        Set<Integer> hiddenForLegacy = new HashSet<>();
        AccountEditPolicy.hideNativeRestProviderSelectionViews(
                account(ServiceAuthenticator.AUTH_TYPE_PASSWORD, "imap.example.org"), 1, hiddenForLegacy::add);
        assertTrue(hiddenForLegacy.isEmpty());
    }

    @Test
    public void nativeRestPasswordControlsStayVisibleWhileOtherCredentialsAreHidden() {
        Set<Integer> hidden = asSet(AccountEditPolicy.getNativeRestCredentialViewIds());

        assertFalse(hidden.contains(R.id.tvPassword));
        assertFalse(hidden.contains(R.id.tilPassword));
        assertTrue(hidden.contains(R.id.tvCaseSensitive));
        assertTrue(hidden.contains(R.id.tvAppPassword));
        assertTrue(hidden.contains(R.id.btnCertificate));
        assertTrue(hidden.contains(R.id.tvCertificate));
        assertTrue(hidden.contains(R.id.tvRealm));
        assertTrue(hidden.contains(R.id.etRealm));
        assertTrue(hidden.contains(R.id.cbTrust));
        assertFalse(hidden.contains(R.id.etUser));
        assertTrue(hidden.contains(R.id.btnReauthorize));
    }

    @Test
    public void nativeRestPasswordRowShowsFixedMaskAndCannotEditOrPersistIt() {
        EntityAccount gmail = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, "imap.gmail.com");
        EntityAccount zoho = account(ServiceAuthenticator.AUTH_TYPE_ZOHO, "imap.zoho.com");
        EntityAccount graph = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API, "outlook.office365.com");

        for (EntityAccount account : Arrays.asList(gmail, zoho, graph)) {
            account.password = "opaque-oauth-token";
            assertEquals(AccountEditPolicy.NATIVE_REST_PASSWORD_MASK,
                    AccountEditPolicy.getPasswordFieldValue(account));
            assertFalse(AccountEditPolicy.getPasswordFieldValue(account).contains(account.password));
            assertEquals("opaque-oauth-token",
                    AccountEditPolicy.getPasswordForSave(account, AccountEditPolicy.NATIVE_REST_PASSWORD_MASK));
            assertFalse(AccountEditPolicy.shouldEnablePasswordField(account, account.auth_type));
        }
    }

    @Test
    public void legacyPasswordDisplayAndSaveRemainUnchanged() {
        EntityAccount legacy = account(ServiceAuthenticator.AUTH_TYPE_PASSWORD, "imap.example.org");
        legacy.password = "legacy-secret";

        assertEquals("legacy-secret", AccountEditPolicy.getPasswordFieldValue(legacy));
        assertEquals("new-legacy-secret", AccountEditPolicy.getPasswordForSave(legacy, "new-legacy-secret"));
    }

    @Test
    public void nativeRestPasswordPencilRoutesToProviderAuthorizationFragment() {
        EntityAccount gmail = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, "imap.gmail.com");
        EntityAccount zoho = account(ServiceAuthenticator.AUTH_TYPE_ZOHO, "imap.zoho.com");
        EntityAccount graph = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API, "outlook.office365.com");

        assertSame(FragmentGmailApi.class,
                AccountEditPolicy.getNativeRestReauthorizationFragmentClass(gmail));
        assertSame(FragmentOAuth.class,
                AccountEditPolicy.getNativeRestReauthorizationFragmentClass(zoho));
        assertSame(FragmentOAuth.class,
                AccountEditPolicy.getNativeRestReauthorizationFragmentClass(graph));
        assertNull(AccountEditPolicy.getNativeRestReauthorizationFragmentClass(
                account(ServiceAuthenticator.AUTH_TYPE_PASSWORD, "imap.example.org")));
    }

    @Test
    public void hiddenImapAdvancedFieldsDoNotIncludeRestScheduleOrLocalSettings() {
        Set<Integer> hidden = asSet(AccountEditPolicy.getNativeRestImapAdvancedViewIds());

        assertTrue(hidden.contains(R.id.cbNoop));
        assertTrue(hidden.contains(R.id.cbPartialFetch));
        assertTrue(hidden.contains(R.id.cbRawFetch));
        assertTrue(hidden.contains(R.id.cbIgnoreSize));
        assertTrue(hidden.contains(R.id.rgDate));
        assertTrue(hidden.contains(R.id.cbUnicode));
        assertFalse(hidden.contains(R.id.cbSynchronize));
        assertFalse(hidden.contains(R.id.cbIgnoreSchedule));
        assertFalse(hidden.contains(R.id.cbOnDemand));
        assertFalse(hidden.contains(R.id.cbPrimary));
        assertFalse(hidden.contains(R.id.cbNotify));
        assertFalse(hidden.contains(R.id.cbAutoSeen));
        assertFalse(hidden.contains(R.id.etInterval));
        assertFalse(hidden.contains(R.id.cbUnmeteredOnly));
        assertFalse(hidden.contains(R.id.cbVpnOnly));
    }

    private static EntityAccount account(int authType, String host) {
        EntityAccount account = new EntityAccount();
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = authType;
        account.host = host;
        return account;
    }

    private static EmailProvider provider(String id, String host) {
        EmailProvider provider = new EmailProvider(id);
        provider.id = id;
        provider.imap.host = host;
        return provider;
    }

    private static Set<Integer> asSet(int[] ids) {
        Set<Integer> result = new HashSet<>();
        for (int id : ids)
            result.add(id);
        return result;
    }
}
