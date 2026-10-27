package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM formatter and source-contract guards; no live accounts or Android services. */
public class ProviderLoggingContractTest {
    @Test
    public void nativeSyncLogsUseSafeFailuresAndDoNotImplicitlyAppendAccountNames() throws Exception {
        String source = readSource("ServiceSynchronize.java");
        int start = source.indexOf("    private void monitorGmailAccount(");
        int zoho = source.indexOf("    private void monitorZohoAccount(", start);
        int end = source.indexOf("    private void ", zoho + 10);
        assertTrue(start >= 0 && zoho > start && end > zoho);
        String nativeMonitors = source.substring(start, end);
        for (String provider : new String[]{"Gmail", "Microsoft Graph", "Zoho"}) {
            assertTrue(nativeMonitors.contains("Log.e(\"" + provider +
                    " REST \" + NativeApiDiagnostics.formatFailure(ex));"));
        }
        assertFalse(nativeMonitors.contains("account.name"));
        assertFalse(nativeMonitors.contains("EntityLog.Type.Account, account,"));
        // UI/database errors must remain informative; only log sinks are sanitized.
        assertTrue(nativeMonitors.contains("setAccountError(account.id, Log.formatThrowable(ex))"));
    }

    @Test
    public void contentAndSendFailureLogsDoNotReuseUserFacingErrorText() throws Exception {
        String content = readSource("NativeApiContentOperations.java");
        assertTrue(content.contains("String diagnostic = NativeApiDiagnostics.formatFailure(ex);"));
        assertTrue(content.contains("failed; user may retry: \" + diagnostic"));
        assertFalse(content.contains("failed; user may retry: \" + value"));
        assertTrue(content.contains("db.attachment().setError(attachmentId, value)"));
        String send = readSource("ServiceSend.java");
        assertTrue(send.contains("EntityLog.log(this, \"Send \" + NativeApiDiagnostics.formatFailure(ex));"));
        assertFalse(send.contains("EntityLog.log(this, \"Send \" + Log.formatThrowable(ex, false));"));
    }

    @Test
    public void authorizationLogsOmitStateUrisAndTokenPayloads() throws Exception {
        String oauth = readSource("FragmentOAuth.java");
        assertFalse(oauth.contains("\" uri=\" + authRequest.toUri()"));
        assertFalse(oauth.contains("\" state=\" + auth.state"));
        assertFalse(oauth.contains("\"token payload=\" + payload"));
        assertFalse(oauth.contains("\"jwt payload=\" + payload"));
        assertTrue(oauth.contains("OAuth request provider=\" + provider.id"));
    }

    @Test
    public void providerFailureLogsContainOnlySafeStatusAndCategory() {
        Throwable[] failures = {
                new GmailApi.ApiException(401, "Gmail body contains user@example.com"),
                new ZohoApi.ApiException(429, -1),
                new GraphMailApi.ApiException(500, -1, "private Graph payload")
        };
        String[] expected = {"failure=http status=401", "failure=http status=429",
                "failure=http status=500"};
        for (int i = 0; i < failures.length; i++)
            assertEquals(expected[i], NativeApiDiagnostics.formatFailure(failures[i]));
    }

    private static String readSource(String name) throws Exception {
        String relative = "src/main/java/in/anoopbhatia/mail/" + name;
        Path path = Paths.get(relative);
        if (!Files.exists(path))
            path = Paths.get("app", relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
