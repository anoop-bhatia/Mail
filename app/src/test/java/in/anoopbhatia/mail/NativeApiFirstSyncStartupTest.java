package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** JVM routing and source-contract guards; these do not instantiate Android services or a live account. */
public class NativeApiFirstSyncStartupTest {
    @Test
    public void nativeAccountsRemainNativeDespiteSharedImapShapedSchema() {
        int[] types = {ServiceAuthenticator.AUTH_TYPE_GRAPH_API,
                ServiceAuthenticator.AUTH_TYPE_GMAIL, ServiceAuthenticator.AUTH_TYPE_ZOHO};
        String[] hosts = {"graph.microsoft.com", "gmail.googleapis.com", "mail.zoho.com"};
        for (int i = 0; i < types.length; i++) {
            EntityAccount account = new EntityAccount();
            account.protocol = EntityAccount.TYPE_IMAP;
            account.auth_type = types[i];
            account.host = hosts[i];
            account.synchronize = true;
            assertTrue(account.isNativeRestApi());
            assertFalse(account.supportsLegacyImapSearch());
            assertEquals(i == 0, account.isGraphApi());
            assertEquals(i == 1, account.isGmailApi());
            assertEquals(i == 2, account.isZohoApi());
        }
    }

    @Test
    public void legacyMicrosoftImapAndPopAccountsAreNotReclassified() {
        for (int protocol : new int[]{EntityAccount.TYPE_IMAP, EntityAccount.TYPE_POP}) {
            EntityAccount account = new EntityAccount();
            account.protocol = protocol;
            account.auth_type = ServiceAuthenticator.AUTH_TYPE_OAUTH;
            account.host = "outlook.office365.com";
            account.provider = "outlook";
            assertFalse(account.isNativeRestApi());
            assertFalse(account.isGraphApi());
            assertEquals(protocol == EntityAccount.TYPE_IMAP, account.supportsLegacyImapSearch());
        }
    }

    @Test
    public void graphAccountIsCommittedEnabledAndNativeBeforeFirstServiceEvaluation() throws Exception {
        String source = readSource("FragmentOAuth.java");
        String graph = section(source, "private void onGraphMailAuthorized(", "private static String getZohoFolderType(");
        assertTrue(graph.contains("account.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;"));
        assertTrue(graph.contains("account.provider = GraphOAuth.PROVIDER_ID;"));
        assertTrue(graph.contains("account.password = password;"));
        assertTrue(graph.contains("account.synchronize = true;"));
        assertTrue(graph.contains("db.account().insertAccount(account)"));
        assertTrue(graph.contains("NativeApiFolderDefaults.applyCreationDefaults(folder, newAccount, true)"));
        assertTrue(graph.contains("EntityOperation.sync(context, folder.id, true)"));
        assertTrue(graph.contains("if (created && Boolean.TRUE.equals(folder.synchronize))"));
        assertFalse("Inbox must not be excluded from durable startup work", graph.contains("!EntityFolder.INBOX.equals(type)"));
        int transaction = transactionStart(graph);
        int commit = commitBoundary(graph, transaction);
        int evaluate = graph.indexOf("ServiceSynchronize.eval(context, \"Microsoft Graph API authorization\")");
        assertTrue("Start synchronization only after the transaction is closed", evaluate > commit);
        int queue = graph.indexOf("EntityOperation.sync(context, folder.id, true)");
        assertTrue("Initial work must be queued inside the account transaction",
                queue > transaction && queue < commit);
        assertFalse("Graph account saving/startup must not depend on debug mode", graph.contains("BuildConfig.DEBUG"));
        assertFalse("Graph setup must not create a legacy transport", graph.contains("new EmailService("));
    }

    @Test
    public void newGraphInboxIsEligibleForDurableInitialSyncButReauthorizationDoesNotResetIt() {
        EntityFolder inbox = new EntityFolder("Inbox", EntityFolder.INBOX);
        inbox.graph_folder_id = "graph-inbox-fixture";
        NativeApiFolderDefaults.applyCreationDefaults(inbox, true, true);
        assertTrue(inbox.synchronize);
        assertTrue(NativeApiFolderDefaults.shouldQueueInitialSync(true, true, inbox));
        assertFalse(NativeApiFolderDefaults.shouldQueueInitialSync(false, true, inbox));
        assertFalse(NativeApiFolderDefaults.shouldQueueInitialSync(true, false, inbox));
        inbox.synchronize = false;
        NativeApiFolderDefaults.applyCreationDefaults(inbox, false, false);
        assertFalse(inbox.synchronize);
    }

    @Test
    public void nativeDispatchReturnsBeforeLegacyTransportAndIsNotDebugGated() throws Exception {
        String source = readSource("ServiceSynchronize.java");
        String dispatch = section(source, "private void monitorAccount(", "final PowerManager pm =");
        for (String call : new String[]{"monitorGraphAccount(account, state);",
                "monitorZohoAccount(account, state);", "monitorGmailAccount(account, state);"})
            assertTrue("Native dispatch must return before the IMAP/POP path: " + call,
                    dispatch.contains(call + "\n            return;"));
        assertFalse(dispatch.contains("BuildConfig.DEBUG"));
    }

    @Test
    public void graphFirstInboxPassDoesNotWaitForAPollOrQueuedInboxOperation() throws Exception {
        String source = readSource("ServiceSynchronize.java");
        String graph = section(source, "private void monitorGraphAccount(", "private int syncRequestedGraphFolders(");
        int inbox = graph.indexOf("GraphMailReceiver.syncInbox(");
        int ordinaryWait = graph.indexOf("state.acquire(waitDelay, false)");
        assertTrue("The initial Graph monitor pass must synchronize Inbox before its poll wait",
                inbox >= 0 && ordinaryWait > inbox);
        assertFalse("Release/Large must execute the same initial Graph pass", graph.contains("BuildConfig.DEBUG"));
        assertTrue(graph.contains("authenticator.refreshToken(false)"));
    }

    @Test
    public void graphSelectsExactDebugAndProductionRedirectsWithoutSharingThem() {
        ApiCredentials.Entry graph = ApiCredentials.get("graph");
        assertEquals(graph.redirectUri(true), GraphOAuth.getRedirectUri(true));
        assertEquals(graph.redirectUri(false), GraphOAuth.getRedirectUri(false));
        assertFalse(graph.redirectUri(true).isEmpty());
        assertFalse(graph.redirectUri(false).isEmpty());
        assertNotEquals(graph.redirectUri(true), graph.redirectUri(false));
        // One Microsoft public-client registration is configured with two different redirects.
        assertEquals(graph.clientId(true), graph.clientId(false));
    }

    @Test
    public void allFoldersConnectingFeedbackFollowsCommittedWorkAndServiceWakeup() throws Exception {
        for (String name : new String[]{"FragmentMessages.java", "FragmentFolders.java"}) {
            String refresh = section(readSource(name),
                    "private void refresh(boolean force)", "protected void onException(");
            int transaction = transactionStart(refresh);
            int commit = commitBoundary(refresh, transaction);
            int queue = refresh.indexOf("EntityOperation.sync(context, folder.id, true, force");
            int wakeup = refresh.indexOf("ServiceSynchronize.reload(context,");
            int feedback = refresh.indexOf("throw new IllegalArgumentException(context.getString(R.string.title_no_connection))");
            assertTrue(name + ": sync operations must commit before waking the service",
                    queue > transaction && queue < commit && wakeup > commit);
            assertTrue(name + ": connecting feedback must not prevent queued Graph work from starting", feedback > wakeup);
            assertTrue(refresh.contains("!\"connected\".equals(account.state)"));
            assertTrue(refresh.contains("if (!now && !force)"));
            if ("FragmentMessages.java".equals(name)) {
                int nativeWork = refresh.indexOf("NativeFolderState.queue(db, account, folder)");
                assertTrue("Graph manual folder work must also be durable before commit", nativeWork > queue && nativeWork < commit);
            }
        }
    }

    /** Index of the durable Room transaction helper call that wraps a fragment's writes. */
    private static int transactionStart(String source) {
        int plain = source.indexOf("runInTransactionWithCleanup(() -> {");
        int cleanup = source.indexOf("callInTransactionWithCleanup(() -> {");
        int start = (plain < 0 ? cleanup : (cleanup < 0 ? plain : Math.min(plain, cleanup)));
        assertTrue("Missing Room transaction helper call", start >= 0);
        return start;
    }

    /**
     * The Room migration replaced raw beginTransaction/endTransaction pairs with the transaction
     * helpers above. Return the index just past the matching closing "});" of the block opened at
     * transactionStart, so these ordering checks still measure a real commit boundary.
     */
    private static int commitBoundary(String source, int transactionStart) {
        int open = source.indexOf("{", transactionStart);
        assertTrue("Missing transaction body", open >= 0);
        int lineStart = source.lastIndexOf('\n', transactionStart) + 1;
        int indent = 0;
        while (lineStart + indent < source.length() && source.charAt(lineStart + indent) == ' ')
            indent++;
        String close = "\n" + " ".repeat(indent) + "});";
        int end = source.indexOf(close, open);
        assertTrue("Missing transaction commit boundary", end > open);
        return end + close.length();
    }

    private static String section(String source, String start, String end) {
        int first = source.indexOf(start);
        int last = source.indexOf(end, first + start.length());
        assertTrue("Missing source section " + start, first >= 0 && last > first);
        return source.substring(first, last);
    }

    private static String readSource(String name) throws Exception {
        String relative = "src/main/java/in/anoopbhatia/mail/" + name;
        for (Path dir = Paths.get(System.getProperty("user.dir")); dir != null; dir = dir.getParent())
            for (Path candidate : new Path[]{dir.resolve(relative), dir.resolve("app").resolve(relative)})
                if (Files.isRegularFile(candidate))
                    return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
        throw new AssertionError("Cannot locate " + name);
    }
}
