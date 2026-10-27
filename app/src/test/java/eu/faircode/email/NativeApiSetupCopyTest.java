package eu.faircode.email;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class NativeApiSetupCopyTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    @Test
    public void nativeFormsKeepBaselineAuthorizationPromptsWithoutPermissionSummaries() throws Exception {
        Document strings = parse(source("values/strings.xml"));
        assertEquals("Authorize access to your Gmail account",
                text(strings, "title_setup_gmail_api_rationale"));
        assertEquals("Authorize access to your Microsoft account",
                text(strings, "title_setup_graph_api_rationale"));
        assertEquals("Authorize access to your Zoho account",
                text(strings, "title_setup_zoho_api_rationale"));
        assertEquals("Authorize access to your %1$s account",
                text(strings, "title_setup_oauth_rationale"));
        assertFalse(hasString(strings, "title_setup_gmail_api_permissions"));
        assertFalse(hasString(strings, "title_setup_graph_api_permissions"));
        assertFalse(hasString(strings, "title_setup_zoho_api_permissions"));
        assertFalse(hasString(strings, "title_setup_gmail_scopes"));

        assertEquals(R.string.title_setup_graph_api_rationale,
                NativeApiSetupCopy.getRationaleResource("outlookgraph", true));
        assertEquals(R.string.title_setup_zoho_api_rationale,
                NativeApiSetupCopy.getRationaleResource(ZohoOAuth.PROVIDER_ID, false));
        assertEquals(0, NativeApiSetupCopy.getRationaleResource("microsoft", false));

        Document gmail = parse(source("layout/fragment_gmail_api.xml"));
        assertEquals("@string/title_setup_gmail_api_rationale",
                byId(gmail, "tvTitle").getAttributeNS(ANDROID, "text"));
        assertEquals("@string/title_privacy_policy",
                byId(gmail, "tvPrivacy").getAttributeNS(ANDROID, "text"));
        assertEquals("@string/app_name",
                byId(gmail, "tvPrivacyApp").getAttributeNS(ANDROID, "text"));
        assertNotNull(byId(gmail, "etName"));
        assertNotNull(byId(gmail, "cbUpdate"));
        assertEquals("@id/cbUpdate",
                byId(gmail, "btnSelect").getAttributeNS(APP, "layout_constraintTop_toBottomOf"));
        assertFalse(hasId(gmail, "tvSelectHint"));

        Document oauth = parse(source("layout/fragment_oauth.xml"));
        assertEquals("@string/title_setup_oauth_rationale",
                byId(oauth, "tvTitle").getAttributeNS(ANDROID, "text"));
        assertEquals("@string/title_privacy_policy",
                byId(oauth, "tvPrivacy").getAttributeNS(ANDROID, "text"));
        assertEquals("@string/app_name",
                byId(oauth, "tvPrivacyApp").getAttributeNS(ANDROID, "text"));
        assertNotNull(byId(oauth, "etName"));
        assertNotNull(byId(oauth, "etEmail"));
        assertNotNull(byId(oauth, "etZohoClientId"));
        assertNotNull(byId(oauth, "etZohoClientSecret"));
        assertNotNull(byId(oauth, "etZohoRedirectUri"));
        assertNotNull(byId(oauth, "cbUpdate"));
        assertNotNull(byId(oauth, "btnOAuth"));
        assertNotNull(byId(oauth, "tvBrowser"));
        assertNotNull(byId(oauth, "btnHelp"));
        assertFalse(hasId(oauth, "tvApiPermissions"));
        assertEquals("@id/tvOutlookPasskeyHint",
                byId(oauth, "tvErrorTitle").getAttributeNS(APP, "layout_constraintTop_toBottomOf"));
    }

    @Test
    public void legacyGmailPermissionNoticeRemainsOnItsExistingScreen() throws Exception {
        Document strings = parse(source("values/strings.xml"));
        assertTrue(hasString(strings, "title_setup_gmail_permissions"));

        Document layout = parse(source("layout/fragment_gmail.xml"));
        assertEquals("@string/title_setup_gmail_permissions",
                byId(layout, "tvSelectHint").getAttributeNS(ANDROID, "text"));
        assertTrue(new String(java.nio.file.Files.readAllBytes(
                source("../java/eu/faircode/email/FragmentGmail.java").toPath()),
                java.nio.charset.StandardCharsets.UTF_8).contains("requestPermissions"));
    }

    @Test
    public void removingSummariesDoesNotChangeRequestedProviderScopes() {
        assertEquals(Arrays.asList(
                        GmailScopes.READ,
                        GmailScopes.MODIFY,
                        GmailScopes.SEND,
                        GmailScopes.PERMANENT_DELETE),
                GmailScopes.getRequested());

        EmailProvider.OAuth graph = GraphOAuth.getProfile();
        assertArrayEquals(new String[]{"offline_access", "https://graph.microsoft.com/Mail.Send"},
                graph.scopes);
        List<String> graphRequest = Arrays.asList(FragmentOAuth.getRequestedScopes(graph.scopes, true));
        assertEquals(Arrays.asList(
                        "offline_access",
                        "https://graph.microsoft.com/Mail.Send",
                        "openid",
                        "profile",
                        "email",
                        "https://graph.microsoft.com/Mail.ReadWrite"),
                graphRequest);

        assertArrayEquals(ZohoOAuth.SCOPES.split(","), ZohoOAuth.createProvider().oauth.scopes);
    }

    private static File source(String relative) {
        String[] bases = {"src/main/res/", "app/src/main/res/"};
        for (String base : bases) {
            File candidate = new File(System.getProperty("user.dir"), base + relative);
            if (candidate.isFile())
                return candidate;
        }
        if ("../java/eu/faircode/email/FragmentGmail.java".equals(relative))
            return new File(System.getProperty("user.dir"), "src/main/java/eu/faircode/email/FragmentGmail.java");
        throw new AssertionError("Cannot locate app resource " + relative + " from " + System.getProperty("user.dir"));
    }

    private static Document parse(File source) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(source);
    }

    private static String text(Document document, String name) {
        NodeList nodes = document.getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (name.equals(node.getAttribute("name")))
                return node.getTextContent();
        }
        throw new AssertionError("Missing string resource " + name);
    }

    private static boolean hasString(Document document, String name) {
        NodeList nodes = document.getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++)
            if (name.equals(((Element) nodes.item(i)).getAttribute("name")))
                return true;
        return false;
    }

    private static Element byId(Document document, String id) {
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id")))
                return node;
        }
        throw new AssertionError("Missing view id " + id);
    }

    private static boolean hasId(Document document, String id) {
        try {
            byId(document, id);
            return true;
        } catch (AssertionError ex) {
            return false;
        }
    }
}
