package eu.faircode.email;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
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
    public void restoredCatalogKeepsLegacyGmailOutlookAndZohoProfilesOnly() throws Exception {
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

        for (String id : Arrays.asList("office365", "office365pcke", "outlookgraph", "outlook")) {
            Element outlook = providers.get(id);
            assertNotNull("Missing legacy Outlook profile " + id, outlook);
            assertTrue("Legacy IMAP profile was removed for " + id,
                    countDirectChildren(outlook, "imap") > 0);
            assertTrue("Legacy SMTP profile was removed for " + id,
                    countDirectChildren(outlook, "smtp") > 0);
            assertEquals("Generic OAuth profile was removed for " + id,
                    1, countDirectChildren(outlook, "oauth"));
        }
        Element outlookGraph = providers.get("outlookgraph");
        assertEquals(1, countDirectChildren(outlookGraph, "pop"));
        assertEquals(0, countDirectChildren(outlookGraph, "graphmail"));
        assertEquals(0, document.getElementsByTagName("graphmail").getLength());
        assertNotNull("Baseline Outlook OAuth provider ID must remain available", outlookGraph);
        assertNull("Native Graph API ID must not replace the baseline Outlook profile",
                providers.get(GraphOAuth.PROVIDER_ID));
        assertNull("Native Zoho API configuration must not live in XML", providers.get(ZohoOAuth.PROVIDER_ID));
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
    public void nativeGraphAuthorizationMetadataIsJavaOwnedAndPreservesExistingValues() throws Exception {
        assertEquals("msgraphapi", GraphOAuth.PROVIDER_ID);
        assertEquals("outlookgraph", GraphOAuth.LEGACY_PROVIDER_ID);
        EmailProvider.OAuth profile = GraphOAuth.getProfile();
        assertTrue(profile.enabled);
        assertTrue(profile.askAccount);
        assertEquals("835c3383-543c-4aa2-98ca-c465eb039987", profile.clientId);
        assertEquals("msauth://eu.faircode.email.debug/PJ0gvpIEttFzi8fXtHIjtsBz%2BT8%3D",
                profile.redirectUri);
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

        String oauth = readSource("src/main/java/eu/faircode/email/FragmentOAuth.java");
        String authenticator = readSource("src/main/java/eu/faircode/email/ServiceAuthenticator.java");
        assertTrue(oauth.contains("return GraphOAuth.getProfile();"));
        assertTrue(authenticator.contains("oauth = GraphOAuth.getProfile();"));
        assertFalse(oauth.contains("provider.graphMail"));
        assertFalse(authenticator.contains("provider.graphMail"));
    }

    @Test
    public void nativeZohoProviderAndOAuthMetadataAreJavaOwned() throws Exception {
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
        assertEquals("", provider.oauth.clientId);
        assertNull("No user client secret may be hard-coded", provider.oauth.clientSecret);
        assertEquals(ZohoOAuth.DEFAULT_REDIRECT_URI, provider.oauth.redirectUri);
        assertEquals(ZohoOAuth.PRIVACY_URI, provider.oauth.privacy);
        assertEquals(ZohoOAuth.getAuthorizationEndpoint(), provider.oauth.authorizationEndpoint);
        assertEquals(ZohoOAuth.getTokenEndpoint(), provider.oauth.tokenEndpoint);
        assertTrue(Arrays.asList(provider.oauth.scopes).contains("ZohoMail.messages.UPDATE"));
        assertEquals("offline", provider.oauth.parameters.get("access_type"));

        String loader = readSource("src/main/java/eu/faircode/email/EmailProvider.java");
        assertTrue(loader.contains("result.add(ZohoOAuth.createProvider())"));
        assertFalse(loader.contains("graphMail = new OAuth()"));
    }

    @Test
    public void gmailApiSetupAndReauthorizationAreIsolatedFromRestoredGmailFlow() throws Exception {
        String legacyGmail = readSource("src/main/java/eu/faircode/email/FragmentGmail.java");
        String gmailApi = readSource("src/main/java/eu/faircode/email/FragmentGmailApi.java");
        String setup = readSource("src/main/java/eu/faircode/email/ActivitySetup.java");
        String policy = readSource("src/main/java/eu/faircode/email/AccountEditPolicy.java");
        String account = readSource("src/main/java/eu/faircode/email/FragmentAccount.java");

        assertTrue("Legacy Gmail account selection was not restored", legacyGmail.contains("newChooseAccountIntent"));
        assertTrue("Legacy Gmail OAuth permission flow was not restored", legacyGmail.contains("requestPermissions"));
        assertFalse("Native Gmail API flow leaked into the baseline fragment",
                legacyGmail.contains("GmailAuthorization.get()"));
        assertTrue(gmailApi.contains("GmailAuthorization.get().authorize"));
        assertTrue(gmailApi.contains("GmailApi(token).getProfile()"));
        assertTrue(setup.contains("FragmentGmailApi fragment = new FragmentGmailApi();"));
        assertTrue(setup.contains("FragmentOAuth fragment = new FragmentOAuth();"));
        assertFalse("Generic Gmail OAuth is still diverted into native API setup",
                setup.contains("if (\"gmail\".equalsIgnoreCase(intent.getStringExtra(\"id\")))"));
        assertTrue(policy.contains("return FragmentGmailApi.class;"));
        assertTrue(account.contains("if (fragmentClass == FragmentGmailApi.class)"));
        assertTrue(account.contains("fragment = new FragmentGmailApi();"));
    }

    @Test
    public void setupAndProviderLinksUseDistinctAppAndVendorPrivacyUrls() throws Exception {
        assertEquals("https://github.com/M66B/FairEmail/blob/master/FAQ.md", Helper.FAQ_BASE_URI);
        assertEquals("https://policies.google.com/privacy",
                firstDirectChild(getProviders(readXml("src/main/res/xml/providers.xml")).get("gmail"), "oauth")
                        .getAttribute("privacy"));
        assertEquals("https://privacy.microsoft.com/privacystatement", GraphOAuth.PRIVACY_URI);
        assertEquals("https://www.zoho.com/privacy.html", ZohoOAuth.PRIVACY_URI);

        String setup = readSource("src/main/java/eu/faircode/email/FragmentSetup.java");
        String oauth = readSource("src/main/java/eu/faircode/email/FragmentOAuth.java");
        String gmailApi = readSource("src/main/java/eu/faircode/email/FragmentGmailApi.java");
        assertTrue(setup.contains("Uri.parse(Helper.FAQ_BASE_URI)"));
        assertTrue(setup.contains(".putExtra(\"privacy\", GraphOAuth.PRIVACY_URI)"));
        assertTrue(setup.contains(".putExtra(\"privacy\", ZohoOAuth.PRIVACY_URI)"));
        assertTrue(oauth.contains("Uri.parse(privacy)"));
        assertTrue(oauth.contains("graphMailApi || ZohoOAuth.isProviderId(id)"));
        assertTrue(oauth.contains("Helper.FAQ_BASE_URI : Helper.PRIVACY_URI"));
        assertTrue(gmailApi.contains("Uri.parse(PRIVACY_URI)"));
        assertTrue(gmailApi.contains("Uri.parse(Helper.FAQ_BASE_URI)"));
    }

    @Test
    public void debugMicrosoftRedirectMatchesAppAuthAndPreservesExistingCallback() throws Exception {
        String androidNamespace = "http://schemas.android.com/apk/res/android";
        String redirectPath = "/PJ0gvpIEttFzi8fXtHIjtsBz+T8=";
        String oldPath = "/xSLzBBuLJunOQPB89rtzM54FXx4=";

        for (String manifestPath : Arrays.asList("src/debug/AndroidManifest.xml",
                "src/main/AndroidManifest.xml", "src/github/AndroidManifest.xml")) {
            Document document = readXml(manifestPath);
            NodeList activities = document.getElementsByTagName("activity");
            boolean newCallback = false;
            boolean oldCallback = false;
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
                        newCallback |= view && defaultCategory && browsable && "msauth".equals(scheme) &&
                                "${applicationId}".equals(host) && redirectPath.equals(path);
                        oldCallback |= "msauth".equals(scheme) && "${namespace}".equals(host) && oldPath.equals(path);
                    }
                }
            }
            if ("src/debug/AndroidManifest.xml".equals(manifestPath))
                assertTrue("New exact redirect is not routed to AppAuth in " + manifestPath, newCallback);
            else
                assertFalse("Debug-only redirect leaked into " + manifestPath, newCallback);
            assertTrue("Existing Microsoft callback was removed from " + manifestPath, oldCallback);
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
}
