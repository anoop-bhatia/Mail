package in.anoopbhatia.mail;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OAuthImapBuildProfileTest {
    @Test
    public void debugBuildUsesItsRegisteredClientAndRedirect() throws Exception {
        XmlPullParser parser = oauth("clientId='release-client' debugClientId='debug-client' " +
                "redirectUri='release:/' debugRedirectUri='debug:/'");
        assertEquals("debug-client", EmailProvider.getOAuthBuildAttribute(parser, "clientId", true));
        assertEquals("debug:/", EmailProvider.getOAuthBuildAttribute(parser, "redirectUri", true));
        assertEquals("release-client", EmailProvider.getOAuthBuildAttribute(parser, "clientId", false));
        assertEquals("release:/", EmailProvider.getOAuthBuildAttribute(parser, "redirectUri", false));
    }

    @Test
    public void sharedMicrosoftClientAndLegacyProfilesFallBackToReleaseAttributes() throws Exception {
        XmlPullParser parser = oauth("clientId='shared-client' redirectUri='release:/' debugRedirectUri='debug:/'");
        assertEquals("shared-client", EmailProvider.getOAuthBuildAttribute(parser, "clientId", true));
        assertEquals("debug:/", EmailProvider.getOAuthBuildAttribute(parser, "redirectUri", true));
        parser = oauth("clientId='legacy-client' redirectUri='legacy:/' debugClientId=' '");
        assertEquals("legacy-client", EmailProvider.getOAuthBuildAttribute(parser, "clientId", true));
        assertEquals("legacy:/", EmailProvider.getOAuthBuildAttribute(parser, "redirectUri", true));
    }

    @Test
    public void newAndExistingMicrosoftIdsKeepOutlookProtocolBehavior() {
        for (String id : new String[]{"microsoft", "office365", "office365pcke", "outlook", "outlookgraph", "msgraphapi"})
            assertTrue(id, EntityAccount.isOutlook(id));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void actualProviderParserLoadsCanonicalOAuthAndAllLegacyProtocolProfiles() throws Exception {
        Path path = Path.of("src/main/res/xml/providers.xml");
        if (!Files.exists(path))
            path = Path.of("app/src/main/res/xml/providers.xml");
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(new StringReader(Files.readString(path)));
        Method parse = EmailProvider.class.getDeclaredMethod("parseProfiles", XmlPullParser.class);
        parse.setAccessible(true);
        List<EmailProvider> providers = (List<EmailProvider>) parse.invoke(null, parser);
        int count = 0;
        for (EmailProvider provider : providers) {
            if (provider.oauth == null)
                continue;
            count++;
            String key = "gmail".equals(provider.id) ? "gmail" : "graph";
            assertTrue("Unexpected generic OAuth profile " + provider.id,
                    "gmail".equals(provider.id) || "microsoft".equals(provider.id));
            assertEquals(ApiCredentials.get(key).clientId(BuildConfig.DEBUG), provider.oauth.clientId);
            assertEquals(ApiCredentials.get(key).redirectUri(BuildConfig.DEBUG), provider.oauth.redirectUri);
            assertNotNull(provider.imap);
            assertNotNull(provider.smtp);
            assertNotNull(provider.pop);
        }
        assertEquals(2, count);
        for (String id : new String[]{"office365", "office365pcke", "outlookgraph", "outlook"})
            assertTrue("Lost protocol profile " + id, providers.stream().anyMatch(p -> id.equals(p.id)));
    }

    private static XmlPullParser oauth(String attributes) throws Exception {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(new StringReader("<oauth " + attributes + " />"));
        parser.nextTag();
        return parser;
    }
}
