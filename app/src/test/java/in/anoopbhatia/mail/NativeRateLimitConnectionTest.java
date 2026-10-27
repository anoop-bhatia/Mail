package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A throttle is a "retry later", not a "cannot connect". All three REST providers must leave the
 * account connected while backing off; Graph and Zoho used to leave it on the "connecting" state
 * written at pass start for the whole retry delay.
 */
public class NativeRateLimitConnectionTest {
    @Test
    public void graphAndZohoThrottlesKeepTheAccountConnected() throws Exception {
        String source = source("ServiceSynchronize");
        for (String[] branch : new String[][]{
                {"Microsoft Graph", graphRateLimitBranch(source)},
                {"Zoho", zohoRateLimitBranch(source)}}) {
            String provider = branch[0];
            String code = branch[1];
            assertTrue(provider, code.contains("markRateLimitedAccountConnected(db, account.id)"));
            assertTrue(provider + " must record backoff before restoring the state",
                    code.indexOf("setAccountBackoff(account.id, rateLimitUntil)") <
                            code.indexOf("markRateLimitedAccountConnected(db, account.id)"));
            assertFalse(provider + " must not clear the account state on a throttle",
                    code.contains("setAccountState(account.id, null)"));
        }
    }

    @Test
    public void restoringAThrottledAccountClearsErrorsAndFolderProgress() throws Exception {
        String helper = helperBody(source("ServiceSynchronize"));
        assertTrue(helper.contains("db.account().setAccountError(accountId, null)"));
        assertTrue(helper.contains("db.account().setAccountState(accountId, \"connected\")"));
        // A folder interrupted mid-pass is left on "connecting" and would render as a failed sync.
        assertTrue(helper.contains("if (\"connecting\".equals(folder.state))"));
        assertTrue(helper.contains("db.folder().setFolderState(folder.id, \"connected\")"));
        assertTrue(helper.contains("db.folder().setFolderError(folder.id, null)"));
        assertTrue(helper.contains("getFolderByType(accountId, EntityFolder.INBOX)"));
    }

    private static String graphRateLimitBranch(String source) {
        return branch(source,
                "if (ex instanceof GraphMailApi.ApiException &&",
                "} else if (ApiNetworkHibernation.isTransportFailure(ex)) {");
    }

    private static String zohoRateLimitBranch(String source) {
        return branch(source,
                "if (ex instanceof ZohoApi.ApiException &&",
                "} else if (ApiNetworkHibernation.isTransportFailure(ex)) {");
    }

    private static String helperBody(String source) {
        return branch(source,
                "private static void markRateLimitedAccountConnected(",
                "\n    private static void setPendingGmailFolderStates(");
    }

    private static String branch(String source, String start, String end) {
        int from = source.indexOf(start);
        assertTrue("Missing: " + start, from >= 0);
        int to = source.indexOf(end, from);
        assertTrue("Missing: " + end, to > from);
        return source.substring(from, to);
    }

    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve(name + ".java"));
    }
}
