package in.anoopbhatia.mail;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SetupProviderResourceTest {
    @Test
    public void mainSideMenuKeepsSupportButRemovesReportAndRateEntries() throws Exception {
        String activityView = readSource("src/main/java/in/anoopbhatia/mail/ActivityView.java");

        assertTrue(activityView.contains("R.string.menu_faq"));
        assertFalse(activityView.contains("R.string.menu_issue"));
        assertFalse(activityView.contains("R.string.menu_rate"));
    }

    @Test
    public void catalogHasCanonicalGenericOAuthProfilesAndRetainsLegacyProtocolProfiles() throws Exception {
        Document document = readXml("src/main/res/xml/providers.xml");
        Map<String, Element> providers = getProviders(document);

        Element gmail = providers.get("gmail");
        assertNotNull(gmail);
        assertEquals(1, countDirectChildren(gmail, "imap"));
        assertEquals(1, countDirectChildren(gmail, "smtp"));
        assertEquals(1, countDirectChildren(gmail, "pop"));
        assertEquals(1, countDirectChildren(gmail, "oauth"));
        assertEquals("https://policies.google.com/privacy",
                firstDirectChild(gmail, "oauth").getAttribute("privacy"));

        Element microsoft = providers.get("microsoft");
        assertNotNull(microsoft);
        assertEquals("Microsoft", microsoft.getAttribute("name"));
        assertEquals("Microsoft", microsoft.getAttribute("description"));
        assertEquals("2", microsoft.getAttribute("order"));
        assertEquals(1, countDirectChildren(microsoft, "imap"));
        assertEquals(1, countDirectChildren(microsoft, "smtp"));
        assertEquals(1, countDirectChildren(microsoft, "pop"));
        assertEquals(1, countDirectChildren(microsoft, "oauth"));
        Element microsoftOauth = firstDirectChild(microsoft, "oauth");
        assertEquals("DUMMY_MICROSOFT_CLIENT_ID", microsoftOauth.getAttribute("clientId"));
        assertEquals("msauth://example.invalid/DUMMY_RELEASE_SIGNATURE",
                microsoftOauth.getAttribute("redirectUri"));
        assertEquals("msauth://example.invalid/DUMMY_DEBUG_SIGNATURE",
                microsoftOauth.getAttribute("debugRedirectUri"));
        assertEquals("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
                microsoftOauth.getAttribute("authorizationEndpoint"));
        assertEquals("https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                microsoftOauth.getAttribute("tokenEndpoint"));
        assertEquals("openid,profile,email,offline_access,https://outlook.office.com/IMAP.AccessAsUser.All,https://outlook.office.com/SMTP.Send,https://outlook.office.com/POP.AccessAsUser.All",
                microsoftOauth.getAttribute("scopes"));

        for (String id : Arrays.asList("office365", "office365pcke", "outlookgraph", "outlook")) {
            Element outlook = providers.get(id);
            assertNotNull("Missing legacy Outlook profile " + id, outlook);
            assertTrue("Legacy IMAP profile was removed for " + id,
                    countDirectChildren(outlook, "imap") > 0);
            assertTrue("Legacy SMTP profile was removed for " + id,
                    countDirectChildren(outlook, "smtp") > 0);
            assertEquals("Legacy generic OAuth profile remains for " + id,
                    0, countDirectChildren(outlook, "oauth"));
        }
        Element outlookGraph = providers.get("outlookgraph");
        assertEquals(1, countDirectChildren(outlookGraph, "pop"));
        assertEquals(0, countDirectChildren(outlookGraph, "graphmail"));
        assertEquals(0, document.getElementsByTagName("graphmail").getLength());
        assertNotNull("Baseline Outlook OAuth provider ID must remain available", outlookGraph);
        assertNull("Native Graph API ID must not replace the baseline Outlook profile",
                providers.get(GraphOAuth.PROVIDER_ID));
        assertNull("Native Zoho API configuration must not become an ordinary provider", providers.get(ZohoOAuth.PROVIDER_ID));
        assertNull("Previous native Zoho ID must not appear in the baseline catalog",
                providers.get(ZohoOAuth.LEGACY_PROVIDER_ID));

        NodeList nodes = document.getDocumentElement().getElementsByTagName("provider");
        int legacyZoho = 0;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element provider = (Element) nodes.item(i);
            if (provider.getAttribute("name").startsWith("Zoho")) {
                legacyZoho++;
                assertTrue("Legacy Zoho IMAP profile missing", countDirectChildren(provider, "imap") > 0);
                assertTrue("Legacy Zoho SMTP profile missing", countDirectChildren(provider, "smtp") > 0);
            }
        }
        assertTrue("The original Zoho protocol profiles were removed", legacyZoho > 0);
    }

    @Test
    public void gmailImapManualSetupRetainsOauthAndAppPasswordOptions() throws Exception {
        Element gmail = getProviders(readXml("src/main/res/xml/providers.xml")).get("gmail");
        assertNotNull(gmail);
        assertEquals("true", gmail.getAttribute("appPassword"));
        assertEquals(1, countDirectChildren(gmail, "imap"));
        assertEquals(1, countDirectChildren(gmail, "smtp"));
        assertEquals(1, countDirectChildren(gmail, "oauth"));

        String providerLoader = readSource("src/main/java/in/anoopbhatia/mail/EmailProvider.java");
        assertFalse("Gmail provider discovery must not reject the domain before selecting its IMAP profile",
                providerLoader.contains("if (EntityAccount.isGmailDomain(domain)"));
    }

    @Test
    public void genericOAuthCredentialsMatchNativeApiCredentials() throws Exception {
        Document document = readXml("src/main/res/xml/providers.xml");
        Element apiCredentials = (Element) document.getElementsByTagName("apiCredentials").item(0);
        Map<String, Element> providers = getProviders(document);
        Element gmailApi = findApi(apiCredentials, "gmail");
        Element gmailOauth = firstDirectChild(providers.get("gmail"), "oauth");
        assertEquals(gmailApi.getAttribute("clientId"), gmailOauth.getAttribute("clientId"));
        assertEquals(gmailApi.getAttribute("debugClientId"), gmailOauth.getAttribute("debugClientId"));
        assertEquals(gmailApi.getAttribute("redirectUri"), gmailOauth.getAttribute("redirectUri"));
        assertEquals(gmailApi.getAttribute("debugRedirectUri"), gmailOauth.getAttribute("debugRedirectUri"));
        Element graphApi = findApi(apiCredentials, "graph");
        Element microsoftOauth = firstDirectChild(providers.get("microsoft"), "oauth");
        assertEquals(graphApi.getAttribute("clientId"), microsoftOauth.getAttribute("clientId"));
        assertEquals(graphApi.getAttribute("clientId"), microsoftOauth.getAttribute("debugClientId"));
        assertEquals(graphApi.getAttribute("redirectUri"), microsoftOauth.getAttribute("redirectUri"));
        assertEquals(graphApi.getAttribute("debugRedirectUri"), microsoftOauth.getAttribute("debugRedirectUri"));
        assertEquals(graphApi.getAttribute("authorizationEndpoint"), microsoftOauth.getAttribute("authorizationEndpoint"));
        assertEquals(graphApi.getAttribute("tokenEndpoint"), microsoftOauth.getAttribute("tokenEndpoint"));
    }

    @Test
    public void removedOAuthProfilesKeepTheirOrdinaryMailServerSettings() throws Exception {
        Map<String, Element> providers = getProviders(readXml("src/main/res/xml/providers.xml"));
        for (String id : Arrays.asList("yahoo", "yahoo2", "fastmail", "aol", "mailru", "yandex", "thundermail")) {
            Element provider = providers.get(id);
            assertNotNull("Missing ordinary mail profile " + id, provider);
            assertEquals("OAuth profile remains for " + id, 0, countDirectChildren(provider, "oauth"));
            assertTrue("IMAP profile was removed for " + id, countDirectChildren(provider, "imap") > 0);
            assertTrue("SMTP profile was removed for " + id, countDirectChildren(provider, "smtp") > 0);
            assertFalse("Removed OAuth menu option remains eligible for " + id,
                    SetupOAuthProviderFilter.isEligible(id, false, false, false, false,
                            null, false, false));
        }
        for (String id : Arrays.asList("yahoo", "yahoo2", "fastmail"))
            assertEquals("POP profile was removed for " + id, 1,
                    countDirectChildren(providers.get(id), "pop"));
    }

    @Test
    public void nativeGraphAuthorizationMetadataIsTypedAndPreservesExistingValues() throws Exception {
        assertEquals("msgraphapi", GraphOAuth.PROVIDER_ID);
        assertEquals("outlookgraph", GraphOAuth.LEGACY_PROVIDER_ID);
        EmailProvider.OAuth profile = GraphOAuth.getProfile();
        assertTrue(profile.enabled);
        assertTrue(profile.askAccount);
        assertEquals("DUMMY_MICROSOFT_CLIENT_ID", profile.clientId);
        assertEquals("msauth://example.invalid/DUMMY_RELEASE_SIGNATURE",
                GraphOAuth.RELEASE_REDIRECT_URI);
        assertEquals("msauth://example.invalid/DUMMY_DEBUG_SIGNATURE",
                GraphOAuth.DEBUG_REDIRECT_URI);
        assertEquals(GraphOAuth.DEBUG_REDIRECT_URI, GraphOAuth.getRedirectUri(true));
        assertEquals(GraphOAuth.RELEASE_REDIRECT_URI, GraphOAuth.getRedirectUri(false));
        assertEquals(GraphOAuth.getRedirectUri(BuildConfig.DEBUG), profile.redirectUri);
        assertTrue(readSource("src/debug/AndroidManifest.xml")
                .contains("android:path=\"${microsoftRedirectPath}\""));
        assertTrue(readSource("src/release/AndroidManifest.xml")
                .contains("android:path=\"${microsoftRedirectPath}\""));
        assertEquals("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
                profile.authorizationEndpoint);
        assertEquals("https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                profile.tokenEndpoint);
        assertEquals("login", profile.prompt);
        assertTrue(profile.tokenScopes);
        assertNull("Graph public-client auth must not carry a client secret", profile.clientSecret);
        assertArrayEquals(new String[]{"offline_access", "https://graph.microsoft.com/Mail.Send"},
                profile.scopes);

        String[] requested = FragmentOAuth.getRequestedScopes(profile.scopes, true);
        assertTrue(Arrays.asList(requested).contains("https://graph.microsoft.com/Mail.Send"));
        assertTrue(Arrays.asList(requested).contains("https://graph.microsoft.com/Mail.ReadWrite"));
        assertFalse(Arrays.asList(profile.scopes).contains("https://graph.microsoft.com/Mail.ReadWrite"));

        String oauth = readSource("src/main/java/in/anoopbhatia/mail/FragmentOAuth.java");
        String authenticator = readSource("src/main/java/in/anoopbhatia/mail/ServiceAuthenticator.java");
        assertTrue(oauth.contains("return GraphOAuth.getProfile();"));
        assertTrue(authenticator.contains("oauth = GraphOAuth.getProfile();"));
        assertFalse(oauth.contains("provider.graphMail"));
        assertFalse(authenticator.contains("provider.graphMail"));
    }

    @Test
    public void nativeZohoProviderUsesTypedOAuthMetadata() throws Exception {
        EmailProvider provider = ZohoOAuth.createProvider();
        assertEquals("zohomailapi", provider.id);
        assertEquals("zoho-mail-api", ZohoOAuth.LEGACY_PROVIDER_ID);
        assertEquals("Zoho Mail API", provider.name);
        assertTrue(provider.enabled);
        assertEquals("imap.zoho.com", provider.imap.host);
        assertEquals(993, provider.imap.port);
        assertEquals("smtp.zoho.com", provider.smtp.host);
        assertEquals(465, provider.smtp.port);
        assertNotNull(provider.oauth);
        assertTrue(provider.oauth.enabled);
        assertTrue(provider.oauth.askAccount);
        assertEquals(ZohoOAuth.getDefaultClientId(), provider.oauth.clientId);
        assertNull("No user client secret may be hard-coded", provider.oauth.clientSecret);
        assertEquals(ZohoOAuth.DEFAULT_REDIRECT_URI, provider.oauth.redirectUri);
        assertEquals(ZohoOAuth.PRIVACY_URI, provider.oauth.privacy);
        assertEquals(ZohoOAuth.getAuthorizationEndpoint(), provider.oauth.authorizationEndpoint);
        assertEquals(ZohoOAuth.getTokenEndpoint(), provider.oauth.tokenEndpoint);
        assertTrue(Arrays.asList(provider.oauth.scopes).contains("ZohoMail.folders.ALL"));
        assertTrue(Arrays.asList(provider.oauth.scopes).contains("ZohoMail.messages.UPDATE"));
        assertEquals("offline", provider.oauth.parameters.get("access_type"));

        String loader = readSource("src/main/java/in/anoopbhatia/mail/EmailProvider.java");
        assertTrue(loader.contains("result.add(ZohoOAuth.createProvider())"));
        assertFalse(loader.contains("graphMail = new OAuth()"));
    }

    @Test
    public void gmailApiSetupAndReauthorizationAreIsolatedFromRestoredGmailFlow() throws Exception {
        String legacyGmail = readSource("src/main/java/in/anoopbhatia/mail/FragmentGmail.java");
        String gmailApi = readSource("src/main/java/in/anoopbhatia/mail/FragmentGmailApi.java");
        String setup = readSource("src/main/java/in/anoopbhatia/mail/ActivitySetup.java");
        String policy = readSource("src/main/java/in/anoopbhatia/mail/AccountEditPolicy.java");
        String account = readSource("src/main/java/in/anoopbhatia/mail/FragmentAccount.java");
        String identity = readSource("src/main/java/in/anoopbhatia/mail/FragmentIdentity.java");

        assertTrue("Legacy Gmail account selection was not restored", legacyGmail.contains("newChooseAccountIntent"));
        assertTrue("Legacy Gmail OAuth permission flow was not restored",
                legacyGmail.contains("requestOAuthPermissions.launch(Helper.getOAuthPermissions())"));
        assertFalse("Native Gmail API flow leaked into the baseline fragment",
                legacyGmail.contains("GmailAuthorization.get()"));
        assertTrue(gmailApi.contains("GmailAuthorization.get().authorize"));
        assertTrue(gmailApi.contains("new GmailApi(token)"));
        assertTrue(gmailApi.contains("api.getProfile()"));
        assertTrue(setup.contains("FragmentGmailApi fragment = new FragmentGmailApi();"));
        assertTrue(setup.contains("FragmentOAuth fragment = new FragmentOAuth();"));
        assertFalse("Generic Gmail OAuth is still diverted into native API setup",
                setup.contains("if (\"gmail\".equalsIgnoreCase(intent.getStringExtra(\"id\")))"));
        assertTrue(policy.contains("return FragmentGmailApi.class;"));
        assertTrue(account.contains("if (fragmentClass == FragmentGmailApi.class)"));
        assertTrue(account.contains("FragmentGmailApi.class") &&
                (account.contains("new FragmentGmailApi()") || account.contains("fragmentClass.newInstance()")));
        assertTrue(account.contains("EmailProvider.getImapProviders(context)"));
        assertTrue(account.contains("AccountEditPolicy.isNativeGmailApiAccount(account)"));
        assertFalse("Gmail IMAP accounts must not be routed to Gmail API reauthorization",
                account.contains("currentAccount.isGmail()"));
        assertFalse("Gmail IMAP OAuth accounts must remain in generic OAuth setup",
                account.contains("account != null && account.isGmail()"));
        assertFalse("Gmail IMAP identities must not be classified as native API by hostname",
                identity.contains("EntityAccount.isGmailHost(identity.host)") ||
                        identity.contains("EntityAccount.isGmailHost(etHost.getText().toString())") ||
                        identity.contains("EntityAccount.isGmailHost(host)"));
    }

    @Test
    public void quickSetupRoutesGenericOAuthBeforeLegacyPasswordAndProviderDiscovery() throws Exception {
        String quickSetup = readSource("src/main/java/in/anoopbhatia/mail/FragmentQuickSetup.java");

        int route = quickSetup.indexOf("OtherProviderSetupPolicy.getOAuthProviderId(email)");
        int passwordRequired = quickSetup.indexOf("TextUtils.isEmpty(password)");
        int legacyDiscovery = quickSetup.indexOf("EmailProvider.fromEmail(context, email");
        assertTrue("Generic OAuth quick setup must route by canonical provider ID", route >= 0);
        assertTrue("The Gmail route must run before legacy password validation", route < passwordRequired);
        assertTrue("The Gmail route must run before legacy IMAP provider discovery", route < legacyDiscovery);
        assertTrue(quickSetup.contains("new Intent(ActivitySetup.ACTION_QUICK_OAUTH)"));
        assertTrue(quickSetup.contains(".putExtra(\"id\", oauthProviderId)"));
        assertTrue(quickSetup.contains(".putExtra(\"address\", email)"));
        assertTrue(quickSetup.contains(".putExtra(\"personal\", name)"));
    }

    @Test
    public void setupProviderFormsKeepVendorPrivacyLinksWithoutMailAppLink() throws Exception {
        assertEquals("https://anoopbhatia.in", Helper.FAQ_BASE_URI);
        assertEquals("https://policies.google.com/privacy",
                firstDirectChild(getProviders(readXml("src/main/res/xml/providers.xml")).get("gmail"), "oauth")
                        .getAttribute("privacy"));
        assertEquals("https://privacy.microsoft.com/privacystatement", GraphOAuth.PRIVACY_URI);
        assertEquals("https://www.zoho.com/privacy.html", ZohoOAuth.PRIVACY_URI);

        String setup = readSource("src/main/java/in/anoopbhatia/mail/FragmentSetup.java");
        String oauth = readSource("src/main/java/in/anoopbhatia/mail/FragmentOAuth.java");
        String gmailApi = readSource("src/main/java/in/anoopbhatia/mail/FragmentGmailApi.java");
        assertFalse("The account setup screen should not expose the removed free-app FAQ link",
                setup.contains("Helper.viewFAQ(v.getContext(), 19);"));
        assertTrue(setup.contains(".putExtra(\"privacy\", GraphOAuth.PRIVACY_URI)"));
        assertTrue(setup.contains(".putExtra(\"privacy\", ZohoOAuth.PRIVACY_URI)"));
        assertTrue(oauth.contains("Uri.parse(privacy)"));
        assertFalse(oauth.contains("tvPrivacyApp"));
        assertTrue(gmailApi.contains("Uri.parse(PRIVACY_URI)"));
        assertFalse(gmailApi.contains("tvPrivacyApp"));
    }

    @Test
    public void nativeApiRedirectsMatchProviderXmlAndPreserveExistingCallback() throws Exception {
        String androidNamespace = "http://schemas.android.com/apk/res/android";
        Document providers = readXml("src/main/res/xml/providers.xml");
        Element apiCredentials = (Element) providers.getElementsByTagName("apiCredentials").item(0);
        Map<String, Element> providerMap = getProviders(providers);
        for (String key : Arrays.asList("graph", "gmail")) {
            String apiDebug = findApi(apiCredentials, key).getAttribute("debugRedirectUri");
            String apiRelease = findApi(apiCredentials, key).getAttribute("redirectUri");
            String id = "graph".equals(key) ? "microsoft" : "gmail";
            Element oauth = firstDirectChild(providerMap.get(id), "oauth");
            assertEquals(apiDebug, oauth.getAttribute("debugRedirectUri"));
            assertEquals(apiRelease, oauth.getAttribute("redirectUri"));
            URI debug = new URI(apiDebug);
            URI release = new URI(apiRelease);
            assertTrue("Graph callback uses the dummy authority", apiDebug.contains("example.invalid") || "gmail".equals(key));
            assertTrue(debug.getScheme() != null && !debug.getScheme().isEmpty());
            assertTrue(release.getScheme() != null && !release.getScheme().isEmpty());
            if ("graph".equals(key)) {
                assertTrue(debug.getHost() != null && !debug.getHost().isEmpty());
                assertTrue(release.getHost() != null && !release.getHost().isEmpty());
                for (URI redirect : Arrays.asList(debug, release)) {
                    assertTrue(redirect.getRawPath().endsWith("SIGNATURE"));
                    assertTrue(redirect.getPath().endsWith("SIGNATURE"));
                    assertFalse(redirect.getPath().contains("REGISTERED_SIGNATURE"));
                }
            } else {
                assertNull(debug.getHost());
                assertEquals("/oauth/debug/callback", debug.getPath());
            }
        }
        String oldPath = "/xSLzBBuLJunOQPB89rtzM54FXx4=";

        String gradle = readSource("build.gradle.kts");
        assertTrue(gradle.contains("providers.fileContents("));
        assertTrue(gradle.contains(".asText.get()"));
        assertTrue(gradle.contains("if (apiKey == \"graph\") \"microsoft\" else \"gmail\""));
        for (String component : Arrays.asList("Scheme", "Host", "Path"))
            assertTrue("Missing Gradle placeholder component " + component,
                    gradle.contains("${prefix}Redirect" + component));
        assertTrue(gradle.contains("manifestPlaceholders.putAll(oauthManifestPlaceholders.getValue(\"debug\"))"));
        assertTrue(gradle.contains("manifestPlaceholders.putAll(oauthManifestPlaceholders.getValue(\"release\"))"));
        assertTrue(gradle.contains("require(apiRedirect == imapRedirect)"));
        assertTrue(gradle.contains("to redirect.path"));

        Element zoho = findApi(apiCredentials, "zoho");
        assertEquals("in.anoopbhatia.mail://oauth2redirect", zoho.getAttribute("redirectUri"));
        assertFalse("Zoho Debug must currently fall back to Release", zoho.hasAttribute("debugRedirectUri"));
        URI zohoRedirect = new URI(zoho.getAttribute("redirectUri"));
        assertEquals("in.anoopbhatia.mail", zohoRedirect.getScheme());
        assertEquals("oauth2redirect", zohoRedirect.getHost());
        assertEquals("", zohoRedirect.getPath());
        assertEquals(zoho.getAttribute("redirectUri"), ZohoOAuth.DEFAULT_REDIRECT_URI);
        assertTrue(gradle.contains("zohoCallback(variant == \"debug\")"));
        assertTrue(gradle.contains("\"zohoRedirectScheme\" to redirect.scheme"));
        assertTrue(gradle.contains("\"zohoRedirectHost\" to redirect.host"));
        assertTrue(gradle.contains("debugRedirectUri[normalize-space(.) != '']"));
        assertTrue("Large must inherit Release callback placeholders",
                gradle.contains("initWith(getByName(\"release\"))"));

        for (String manifestPath : Arrays.asList("src/debug/AndroidManifest.xml",
                "src/release/AndroidManifest.xml", "src/main/AndroidManifest.xml",
                "src/large/AndroidManifest.xml")) {
            boolean debug = "src/debug/AndroidManifest.xml".equals(manifestPath);
            boolean nativeOverlay = !"src/main/AndroidManifest.xml".equals(manifestPath);
            String manifestSource = readSource(manifestPath);
            if (nativeOverlay) {
                for (String placeholder : Arrays.asList("${microsoftRedirectScheme}", "${microsoftRedirectHost}",
                        "${microsoftRedirectPath}", "${gmailRedirectScheme}", "${gmailRedirectPath}",
                        "${zohoRedirectScheme}", "${zohoRedirectHost}"))
                    assertTrue("Missing callback placeholder in " + manifestPath,
                            manifestSource.contains(placeholder));
            }
            if (nativeOverlay)
                assertFalse("Manifest must not duplicate the Graph callback literal",
                        manifestSource.contains("/ZoV23za8F42kISLSPNsPoCTNv7E="));
            Document document = readXml(manifestPath);
            NodeList activities = document.getElementsByTagName("activity");
            boolean oldCallback = false;
            boolean graphCallback = false;
            boolean gmailCallback = false;
            boolean zohoCallback = false;
            boolean genericCallback = false;
            for (int i = 0; i < activities.getLength(); i++) {
                Element activity = (Element) activities.item(i);
                if (!"net.openid.appauth.RedirectUriReceiverActivity".equals(
                        activity.getAttributeNS(androidNamespace, "name")))
                    continue;

                NodeList filters = activity.getElementsByTagName("intent-filter");
                for (int j = 0; j < filters.getLength(); j++) {
                    Element filter = (Element) filters.item(j);
                    boolean view = false;
                    boolean defaultCategory = false;
                    boolean browsable = false;
                    NodeList actions = filter.getElementsByTagName("action");
                    for (int k = 0; k < actions.getLength(); k++)
                        view |= "android.intent.action.VIEW".equals(
                                ((Element) actions.item(k)).getAttributeNS(androidNamespace, "name"));
                    NodeList categories = filter.getElementsByTagName("category");
                    for (int k = 0; k < categories.getLength(); k++) {
                        String category = ((Element) categories.item(k)).getAttributeNS(androidNamespace, "name");
                        defaultCategory |= "android.intent.category.DEFAULT".equals(category);
                        browsable |= "android.intent.category.BROWSABLE".equals(category);
                    }

                    NodeList data = filter.getElementsByTagName("data");
                    for (int k = 0; k < data.getLength(); k++) {
                        Element uri = (Element) data.item(k);
                        String scheme = uri.getAttributeNS(androidNamespace, "scheme");
                        String host = uri.getAttributeNS(androidNamespace, "host");
                        String path = uri.getAttributeNS(androidNamespace, "path");
                        graphCallback |= view && defaultCategory && browsable &&
                                "${microsoftRedirectScheme}".equals(scheme) &&
                                "${microsoftRedirectHost}".equals(host) &&
                                "${microsoftRedirectPath}".equals(path);
                        gmailCallback |= view && defaultCategory && browsable &&
                                "${gmailRedirectScheme}".equals(scheme) &&
                                "${gmailRedirectPath}".equals(path);
                        zohoCallback |= view && defaultCategory && browsable &&
                                "${zohoRedirectScheme}".equals(scheme) &&
                                "${zohoRedirectHost}".equals(host) && path.isEmpty();
                        genericCallback |= view && defaultCategory && browsable &&
                                "${namespace}".equals(scheme) && host.isEmpty() && path.isEmpty();
                        oldCallback |= "msauth".equals(scheme) && "${namespace}".equals(host) && oldPath.equals(path);
                    }
                }
            }
            if (nativeOverlay) {
                assertTrue("Graph API redirect is not routed to AppAuth in " + manifestPath, graphCallback);
                assertTrue("Gmail API redirect is not routed to AppAuth in " + manifestPath, gmailCallback);
                assertTrue("Zoho API redirect is not routed to AppAuth in " + manifestPath, zohoCallback);
            }
            if ("src/debug/AndroidManifest.xml".equals(manifestPath) ||
                    "src/main/AndroidManifest.xml".equals(manifestPath)) {
                assertTrue("Existing Microsoft callback was removed from " + manifestPath, oldCallback);
                assertTrue("Existing generic callback was removed from " + manifestPath, genericCallback);
            }
        }
    }

    @Test
    public void classicCardUsesGenericApiChipsAndKeepsGmailSpecificLabels() throws Exception {
        Document strings = readXml("src/main/res/values/strings.xml");
        Element api = findString(strings, "title_setup_api");
        assertNotNull(api);
        assertEquals("API", api.getTextContent());
        assertEquals("false", api.getAttribute("translatable"));
        assertEquals("Gmail API", findString(strings, "title_account_gmail_api").getTextContent());

        String setup = readSource("src/main/res/layout/fragment_setup.xml");
        assertTrue(setup.contains("android:id=\"@+id/tvApiReceive\""));
        assertTrue(setup.contains("android:id=\"@+id/tvApiSend\""));
        assertEquals(2, count(setup, "android:text=\"@string/title_setup_api\""));
        assertTrue(setup.contains("android:text=\"@string/title_aliases\""));
        assertTrue(setup.contains("android:text=\"@string/title_setup_account_remark\""));
        assertTrue(setup.contains("android:text=\"@string/title_setup_identity_remark\""));
        String identity = readSource("src/main/res/layout/fragment_identity.xml");
        assertTrue(identity.contains("android:text=\"@string/title_account_gmail_api\""));
    }

    private static int count(String value, String needle) {
        int result = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length())
            result++;
        return result;
    }

    private static Document readXml(String relativePath) throws Exception {
        Path file = findSource(relativePath);
        assertNotNull("Could not locate " + relativePath + " from " + Paths.get(System.getProperty("user.dir")), file);
        File source = file.toFile();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(source);
    }

    private static String readSource(String relativePath) throws Exception {
        Path file = findSource(relativePath);
        assertNotNull("Could not locate " + relativePath + " from " + Paths.get(System.getProperty("user.dir")), file);
        return new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Path findSource(String relativePath) {
        Path working = Paths.get(System.getProperty("user.dir"));
        for (Path directory = working; directory != null; directory = directory.getParent()) {
            Path modulePath = directory.resolve(relativePath);
            Path repoPath = directory.resolve("app").resolve(relativePath);
            if (Files.isRegularFile(modulePath))
                return modulePath;
            if (Files.isRegularFile(repoPath))
                return repoPath;
        }
        return null;
    }

    private static Map<String, Element> getProviders(Document document) {
        Map<String, Element> result = new HashMap<>();
        NodeList nodes = document.getDocumentElement().getElementsByTagName("provider");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element provider = (Element) nodes.item(i);
            String id = provider.getAttribute("id");
            if (!id.isEmpty())
                result.put(id, provider);
        }
        return result;
    }

    private static int countDirectChildren(Element parent, String name) {
        int count = 0;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && name.equals(child.getNodeName()))
                count++;
        }
        return count;
    }

    private static Element firstDirectChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && name.equals(child.getNodeName()))
                return (Element) child;
        }
        return null;
    }

    private static Element findString(Document document, String name) {
        NodeList nodes = document.getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element string = (Element) nodes.item(i);
            if (name.equals(string.getAttribute("name")))
                return string;
        }
        return null;
    }

    private static Element findApi(Element apiCredentials, String key) {
        NodeList nodes = apiCredentials.getElementsByTagName("api");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element api = (Element) nodes.item(i);
            if (key.equals(api.getAttribute("key")))
                return api;
        }
        return null;
    }
}
