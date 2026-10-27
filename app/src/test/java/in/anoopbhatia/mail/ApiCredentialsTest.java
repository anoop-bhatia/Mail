package in.anoopbhatia.mail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.*;

public class ApiCredentialsTest {
    @Test
    public void sourceContainsExactlyTheSeparateNativeEntries() {
        assertEquals(Arrays.asList("gmail", "graph", "zoho"),
                new ArrayList<>(ApiCredentials.all().keySet()));
        ApiCredentials.Entry gmail = ApiCredentials.get("gmail");
        ApiCredentials.Entry graph = ApiCredentials.get("graph");
        ApiCredentials.Entry zoho = ApiCredentials.get("zoho");
        assertEquals("gmailapi", gmail.providerId);
        assertEquals("msgraphapi", graph.providerId);
        assertEquals("outlookgraph", graph.legacyProviderId);
        assertEquals("zohomailapi", zoho.providerId);
        assertEquals("zoho-mail-api", zoho.legacyProviderId);
        assertNotNull(gmail.prompt);
        assertNotNull(graph.prompt);
        assertNotNull(zoho.prompt);
        assertNotNull(zoho.authorizationServer);
        assertTrue(zoho.parameters.containsKey("access_type"));
        assertNotNull(zoho.clientSecret);
        assertFalse(zoho.clientSecret.isEmpty());
        assertNull(gmail.clientSecret);
        assertNull(graph.clientSecret);
    }

    @Test
    public void debugAndReleaseRegistrationsAndFallbacksArePreserved() {
        ApiCredentials.Entry gmail = ApiCredentials.get("gmail");
        assertFalse(gmail.clientId(false).isEmpty());
        assertFalse(gmail.clientId(true).isEmpty());
        assertNotEquals(gmail.clientId(false), gmail.clientId(true));
        assertFalse(gmail.redirectUri(false).isEmpty());
        assertFalse(gmail.redirectUri(true).isEmpty());
        assertNotEquals(gmail.redirectUri(false), gmail.redirectUri(true));
        ApiCredentials.Entry graph = ApiCredentials.get("graph");
        assertEquals(graph.clientId(false), graph.clientId(true));
        assertNotEquals(graph.redirectUri(false), graph.redirectUri(true));
        ApiCredentials.Entry zoho = ApiCredentials.get("zoho");
        assertEquals(zoho.clientId(false), zoho.clientId(true));
        assertEquals(zoho.redirectUri(false), zoho.redirectUri(true));
    }

    @Test
    public void scopeOrderAndRegionalAccountsServersArePreserved() {
        assertFalse(ApiCredentials.get("gmail").scopes.isEmpty());
        assertFalse(ApiCredentials.get("graph").scopes.isEmpty());
        assertFalse(ApiCredentials.get("graph").additionalScopes.isEmpty());
        ApiCredentials.Entry zoho = ApiCredentials.get("zoho");
        assertFalse(zoho.scopes.isEmpty());
        assertFalse(zoho.accountsServers.isEmpty());
        for (String uri : zoho.accountsServers.values())
            assertNotNull(uri);
        assertEquals(zoho.authorizationServer, zoho.accountsServers.get("us"));
    }

    @Test
    public void collectionsAreImmutableAndToStringDoesNotExposeCredentials() {
        ApiCredentials.Entry entry = ApiCredentials.get("zoho");
        assertImmutable(() -> entry.scopes.add("x"));
        assertImmutable(() -> entry.additionalScopes.add("x"));
        assertImmutable(() -> entry.parameters.put("x", "y"));
        assertImmutable(() -> entry.accountsServers.put("x", "y"));
        assertImmutable(() -> ApiCredentials.all().clear());
        assertFalse(entry.toString().contains(entry.clientSecret));
        assertFalse(entry.toString().contains(entry.clientId));
    }

    @Test
    public void parserIgnoresOrdinaryProviderOAuthAndNestedLookalikeSections() {
        String xml = fixture().replace("<providers>",
                "<providers><provider id='ordinary'><oauth clientSecret='fixture-only'/>" +
                        "<apiCredentials><api key='ignored'/></apiCredentials></provider>");
        Map<String, ApiCredentials.Entry> entries = parse(xml);
        assertEquals(3, entries.size());
        assertFalse(entries.containsKey("ordinary"));
        assertEquals("fixture-client", entries.get("gmail").clientId);
    }

    @Test
    public void parserUsesOnlyDirectCredentialSectionChildrenAndIgnoresOtherRootSections() {
        String xml = fixture().replace("<providers>",
                "<providers><other><apiCredentials><api key='nested'/></apiCredentials></other>" +
                        "<metadata><api key='nested-too'/></metadata>");
        Map<String, ApiCredentials.Entry> entries = parse(xml);
        assertEquals(3, entries.size());
        assertFalse(entries.containsKey("nested"));
        assertFalse(entries.containsKey("nested-too"));
    }

    @Test
    public void scopeTextIncludesNestedTextCdataAndEntities() {
        String xml = fixture().replace("<scope>fixture-scope</scope>",
                "<scope> leading <![CDATA[cdata-&]]><label> nested &amp; text </label> trailing </scope>");
        ApiCredentials.Entry entry = parse(xml).get("gmail");
        assertEquals("leading cdata-& nested & text  trailing", entry.scopes.get(0));
    }

    @Test
    public void onlyTheFirstDirectScopesElementIsUsed() {
        String xml = fixture().replace("<scopes><scope>fixture-scope</scope></scopes>",
                "<scopes><scope>first-scope</scope><wrapper><scope>ignored-nested</scope></wrapper></scopes>" +
                        "<scopes><scope>ignored-second</scope><scope/></scopes>");
        assertEquals(Arrays.asList("first-scope"), parse(xml).get("gmail").scopes);
    }

    @Test
    public void missingAndDuplicateSectionsOrKeysAreRejected() {
        rejected("<providers/>");
        rejected(fixture().replace("</providers>", "<apiCredentials/></providers>"));
        rejected(fixture().replace(entry("gmail"), ""));
        rejected(fixture().replace("</apiCredentials>", entry("gmail") + "</apiCredentials>"));
        rejected(fixture().replace("</apiCredentials>", entry("extra") + "</apiCredentials>"));
        rejected(fixture().replace("providerId='graph-api'", "providerId='gmail-api'"));
    }

    @Test
    public void missingFieldsInvalidUrisAndDuplicateParametersAreRejected() {
        rejected(fixture().replace("clientId='fixture-client'", ""));
        rejected(fixture().replace("tokenEndpoint='https://example.invalid/token'", "tokenEndpoint='not-a-uri'"));
        rejected(fixture().replace("authorizationServer='https://example.invalid'", "authorizationServer='not-a-uri'"));
        rejected(fixture().replace("<scope>fixture-scope</scope>", "<scope/>"));
        rejected(fixture().replace("</api>", "<parameter key='x' value='a'/><parameter key='x' value='b'/></api>"));
        rejected(fixture().replace("</api>", "<accountsServer location='x' uri='https://example.invalid/x'/>" +
                "<accountsServer location='x' uri='https://example.invalid/y'/></api>"));
    }

    @Test
    public void malformedXmlAndDoctypesAreRejectedWithoutDiagnosticValues() {
        rejected(fixture().replace("fixture-client", "private-fixture-value & invalid"));
        rejected("<!DOCTYPE providers [<!ENTITY x SYSTEM 'file:///unavailable'>]>" + fixture());
        rejected("<providers><apiCredentials>");
        rejected(fixture() + "trailing-junk");
        rejected(fixture() + "<extra/>");
        rejected("leading-junk" + fixture());
        rejected(fixture() + "<![CDATA[ ]]>");
    }

    @Test
    public void utf16DoctypeIsRejectedWithoutLeakingFixtureValues() {
        byte[] utf16 = ("\ufeff<!DOCTYPE providers [<!ENTITY x 'private-fixture-value'>]>" + fixture())
                .getBytes(StandardCharsets.UTF_16LE);
        rejected(utf16);
    }

    @Test
    public void unknownDirectSectionElementFailsWithSanitizedError() {
        rejected(fixture().replace("<api key='gmail'", "<unknown private='private-fixture-value'/><api key='gmail'"));
    }

    @Test
    public void unknownKeysFailWithSanitizedErrors() {
        for (String key : Arrays.asList(null, "", "private-fixture-value")) {
            try {
                ApiCredentials.get(key);
                fail("Unknown key must fail");
            } catch (IllegalStateException expected) {
                assertFalse(expected.getMessage().contains("private-fixture-value"));
            }
        }
    }

    private static String fixture() {
        return "<providers><apiCredentials>" + entry("gmail") + entry("graph") + entry("zoho") +
                "</apiCredentials></providers>";
    }

    private static String entry(String key) {
        return "<api key='" + key + "' providerId='" + key + "-api' clientId='fixture-client' " +
                "redirectUri='fixture:/callback' authorizationEndpoint='https://example.invalid/auth' " +
                "tokenEndpoint='https://example.invalid/token' privacy='https://example.invalid/privacy' " +
                "authorizationServer='https://example.invalid' prompt='consent'>" +
                "<scopes><scope>fixture-scope</scope></scopes></api>";
    }

    private static Map<String, ApiCredentials.Entry> parse(String xml) {
        return ApiCredentials.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void rejected(byte[] xml) {
        try {
            ApiCredentials.parse(new ByteArrayInputStream(xml));
            fail("Invalid credential configuration must fail");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().contains("private-fixture-value"));
            assertNull(expected.getCause());
        }
    }

    private static void rejected(String xml) {
        try {
            parse(xml);
            fail("Invalid credential configuration must fail");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().contains("private-fixture-value"));
            assertNull(expected.getCause());
        }
    }

    private static void assertImmutable(Runnable action) {
        try {
            action.run();
            fail("Credential collections must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }
}
