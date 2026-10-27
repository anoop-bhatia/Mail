package in.anoopbhatia.mail;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * Immutable native API OAuth credentials loaded from the shared providers.xml resource.
 * The resource is the single source for Android and plain-Java builds; this loader is
 * deliberately independent of the ordinary IMAP provider parser.
 */
public final class ApiCredentials {
    private static final String RESOURCE = "/xml/providers.xml";
    private static final String[] REQUIRED_KEYS = {"gmail", "graph", "zoho"};

    private ApiCredentials() {
    }

    private static class Holder {
        static final Map<String, Entry> VALUES = load();
    }

    public static Entry get(String key) {
        if (key == null || key.trim().isEmpty())
            throw failure("Missing API credential key");
        Entry entry = Holder.VALUES.get(key);
        if (entry == null)
            throw failure("Unknown API credential key");
        return entry;
    }

    public static Map<String, Entry> all() {
        return Holder.VALUES;
    }

    /** Returns the optional build-time Zoho server override, or the XML default. */
    public static String zohoAuthorizationServer() {
        String fallback = get("zoho").authorizationServer;
        try (InputStream input = ApiCredentials.class.getResourceAsStream("/api-credentials-overrides.properties")) {
            if (input == null)
                return fallback;
            Properties properties = new Properties();
            properties.load(input);
            String value = properties.getProperty("zohoAuthorizationServer");
            if (value == null || value.trim().isEmpty())
                return fallback;
            validateUri(value.trim(), true);
            return value.trim();
        } catch (ApiCredentialsException ex) {
            throw ex;
        } catch (Exception ex) {
            throw failure("Invalid API credentials override");
        }
    }

    /** Package-visible for fixture tests; parses only direct apiCredentials/api elements. */
    static Map<String, Entry> parse(InputStream input) {
        if (input == null)
            throw failure("Missing API credentials resource");
        try {
            // Android supplies this parser. Avoid DOM here: the CSS dependency
            // duplicates platform DOM interfaces and can mislead R8 optimization.
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(input, null);
            Map<String, Entry> result = new LinkedHashMap<>();
            boolean foundRoot = false;
            boolean foundSection = false;
            int sectionDepth = -1;
            int event;
            // Consume the entire document, including content after the section,
            // so malformed trailing XML cannot be silently accepted.
            while ((event = next(parser)) != XmlPullParser.END_DOCUMENT) {
                if (parser.getDepth() == 0 &&
                        (event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF ||
                                (event == XmlPullParser.TEXT && !parser.getText().trim().isEmpty())))
                    throw failure("Invalid API credentials configuration");
                if (event == XmlPullParser.END_TAG && parser.getDepth() == sectionDepth)
                    sectionDepth = -1;
                if (event != XmlPullParser.START_TAG)
                    continue;
                int depth = parser.getDepth();
                if (depth == 1) {
                    if (foundRoot)
                        throw failure("Invalid API credentials configuration");
                    foundRoot = true;
                }
                if (depth == 2 && "apiCredentials".equals(parser.getName())) {
                    if (foundSection)
                        throw failure("Duplicate API credentials section");
                    foundSection = true;
                    sectionDepth = depth;
                } else if (sectionDepth != -1 && depth == sectionDepth + 1) {
                    if (!"api".equals(parser.getName()))
                        throw failure("Unexpected API credentials element");
                    Entry entry = parseEntry(parser);
                    if (result.put(entry.key, entry) != null)
                        throw failure("Duplicate API credential key");
                } else {
                    // Leave the root open, but ignore ordinary provider subtrees.
                    if (depth > 1)
                        skipElement(parser);
                }
            }
            if (!foundSection)
                throw failure("Missing API credentials section");
            for (String key : REQUIRED_KEYS)
                if (!result.containsKey(key))
                    throw failure("Missing API credential entry");
            if (result.size() != REQUIRED_KEYS.length)
                throw failure("Unexpected API credential entry");
            Map<String, String> providerIds = new LinkedHashMap<>();
            for (Entry entry : result.values()) {
                if (providerIds.put(entry.providerId, entry.key) != null)
                    throw failure("Duplicate API provider id");
                if (entry.legacyProviderId != null && providerIds.put(entry.legacyProviderId, entry.key) != null)
                    throw failure("Duplicate API provider id");
            }
            return Collections.unmodifiableMap(result);
        } catch (ApiCredentialsException ex) {
            throw ex;
        } catch (Exception ex) {
            throw failure("Invalid API credentials configuration");
        }
    }

    /** Reject declarations before an entity can be expanded or resolved. */
    private static int next(XmlPullParser parser) throws Exception {
        int event = parser.nextToken();
        if (event == XmlPullParser.DOCDECL ||
                (event == XmlPullParser.ENTITY_REF && parser.getText() == null))
            throw failure("Invalid API credentials configuration");
        return event;
    }

    private static void skipElement(XmlPullParser parser) throws Exception {
        int depth = parser.getDepth();
        int event;
        do {
            event = next(parser);
            if (event == XmlPullParser.END_DOCUMENT)
                throw failure("Invalid API credentials configuration");
        } while (event != XmlPullParser.END_TAG || parser.getDepth() != depth);
    }

    private static Map<String, Entry> load() {
        try (InputStream input = ApiCredentials.class.getResourceAsStream(RESOURCE)) {
            return parse(input);
        } catch (ApiCredentialsException ex) {
            throw ex;
        } catch (Exception ex) {
            throw failure("Unable to load API credentials resource");
        }
    }

    private static Entry parseEntry(XmlPullParser element) throws Exception {
        String key = required(element, "key");
        String providerId = required(element, "providerId");
        String legacy = optional(element, "legacyProviderId");
        String clientId = required(element, "clientId");
        String debugClientId = defaulted(element, "debugClientId", clientId);
        String clientSecret = optional(element, "clientSecret");
        String redirect = required(element, "redirectUri");
        String debugRedirect = defaulted(element, "debugRedirectUri", redirect);
        String authorization = required(element, "authorizationEndpoint");
        String token = required(element, "tokenEndpoint");
        String privacy = required(element, "privacy");
        String authorizationServer = optional(element, "authorizationServer");
        if ("zoho".equals(key) && authorizationServer == null)
            throw failure("Missing API credential field");
        if (authorizationServer != null)
            validateUri(authorizationServer, true);
        String prompt = required(element, "prompt");
        validateUri(authorization, true);
        validateUri(token, true);
        validateUri(privacy, true);
        validateUri(redirect, false);
        validateUri(debugRedirect, false);
        List<String> scopes = new ArrayList<>();
        List<String> additionalScopes = new ArrayList<>();
        String additional = optional(element, "additionalScopes");
        if (additional != null)
            for (String scope : additional.split(","))
                if (!scope.trim().isEmpty()) additionalScopes.add(scope.trim());
        Map<String, String> parameters = new LinkedHashMap<>();
        Map<String, String> accounts = new LinkedHashMap<>();
        boolean foundScopes = false;
        int depth = element.getDepth();
        int event;
        while ((event = next(element)) != XmlPullParser.END_TAG || element.getDepth() != depth) {
            if (event == XmlPullParser.END_DOCUMENT)
                throw failure("Invalid API credentials configuration");
            if (event != XmlPullParser.START_TAG)
                continue;
            switch (element.getName()) {
                case "scopes":
                    // Match the original first-direct-child semantics.
                    if (!foundScopes) {
                        foundScopes = true;
                        parseScopes(element, scopes);
                    } else
                        skipElement(element);
                    break;
                case "parameter":
                    String parameterKey = required(element, "key");
                    String parameterValue = required(element, "value");
                    if (parameters.put(parameterKey, parameterValue) != null)
                        throw failure("Duplicate API credential parameter");
                    skipElement(element);
                    break;
                case "accountsServer":
                    String location = required(element, "location");
                    String uri = required(element, "uri");
                    validateUri(uri, true);
                    if (accounts.put(location, uri) != null)
                        throw failure("Duplicate API accounts server");
                    skipElement(element);
                    break;
                default:
                    skipElement(element);
            }
        }
        if (!foundScopes)
            throw failure("Missing API credential field");
        if (scopes.isEmpty())
            throw failure("Missing API credential scopes");
        return new Entry(key, providerId, legacy, clientId, debugClientId, clientSecret,
                redirect, debugRedirect, authorization, token, privacy, authorizationServer,
                prompt, parameters, scopes, additionalScopes, accounts);
    }

    private static void parseScopes(XmlPullParser parser, List<String> scopes) throws Exception {
        int depth = parser.getDepth();
        int event;
        while ((event = next(parser)) != XmlPullParser.END_TAG || parser.getDepth() != depth) {
            if (event == XmlPullParser.END_DOCUMENT)
                throw failure("Invalid API credentials configuration");
            if (event != XmlPullParser.START_TAG)
                continue;
            if (!"scope".equals(parser.getName())) {
                skipElement(parser);
                continue;
            }
            int scopeDepth = parser.getDepth();
            StringBuilder text = new StringBuilder();
            while ((event = next(parser)) != XmlPullParser.END_TAG || parser.getDepth() != scopeDepth) {
                if (event == XmlPullParser.END_DOCUMENT)
                    throw failure("Invalid API credentials configuration");
                if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT ||
                        event == XmlPullParser.ENTITY_REF || event == XmlPullParser.IGNORABLE_WHITESPACE)
                    text.append(parser.getText());
            }
            String scope = text.toString().trim();
            if (scope.isEmpty())
                throw failure("Invalid API credential scope");
            scopes.add(scope);
        }
    }

    private static String required(XmlPullParser e, String name) {
        String value = optional(e, name);
        if (value == null)
            throw failure("Missing API credential field");
        return value;
    }

    private static String optional(XmlPullParser e, String name) {
        String value = e.getAttributeValue(null, name);
        if (value == null)
            return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static String defaulted(XmlPullParser e, String name, String fallback) {
        String value = optional(e, name);
        return value == null ? fallback : value;
    }

    private static void validateUri(String value, boolean web) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || (web && !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))))
                throw failure("Invalid API credential URI");
        } catch (IllegalArgumentException ex) {
            throw failure("Invalid API credential URI");
        }
    }

    private static ApiCredentialsException failure(String message) {
        return new ApiCredentialsException(message);
    }

    private static final class ApiCredentialsException extends IllegalStateException {
        ApiCredentialsException(String message) { super(message); }
    }

    public static final class Entry {
        public final String key, providerId, legacyProviderId, clientId, debugClientId, clientSecret;
        public final String redirectUri, debugRedirectUri, authorizationEndpoint, tokenEndpoint, privacy, authorizationServer, prompt;
        public final Map<String, String> parameters;
        public final List<String> scopes, additionalScopes;
        public final Map<String, String> accountsServers;

        private Entry(String key, String providerId, String legacyProviderId, String clientId,
                      String debugClientId, String clientSecret, String redirectUri, String debugRedirectUri,
                      String authorizationEndpoint, String tokenEndpoint, String privacy,
                      String authorizationServer, String prompt, Map<String, String> parameters,
                      List<String> scopes, List<String> additionalScopes, Map<String, String> accountsServers) {
            this.key = key; this.providerId = providerId; this.legacyProviderId = legacyProviderId;
            this.clientId = clientId; this.debugClientId = debugClientId; this.clientSecret = clientSecret;
            this.redirectUri = redirectUri; this.debugRedirectUri = debugRedirectUri;
            this.authorizationEndpoint = authorizationEndpoint; this.tokenEndpoint = tokenEndpoint;
            this.privacy = privacy; this.authorizationServer = authorizationServer;
            this.prompt = prompt;
            this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            this.scopes = Collections.unmodifiableList(new ArrayList<>(scopes));
            this.additionalScopes = Collections.unmodifiableList(new ArrayList<>(additionalScopes));
            this.accountsServers = Collections.unmodifiableMap(new LinkedHashMap<>(accountsServers));
        }

        public String clientId(boolean debug) { return debug ? debugClientId : clientId; }
        public String redirectUri(boolean debug) { return debug ? debugRedirectUri : redirectUri; }

        @Override public String toString() {
            return "ApiCredentials.Entry{" + key + ", providerId=" + providerId + "}";
        }
    }
}
