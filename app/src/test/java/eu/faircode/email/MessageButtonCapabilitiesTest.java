package eu.faircode.email;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageButtonCapabilitiesTest {
    private static MessageButtonCapabilities.Context context(MessageButtonCapabilities.Provider provider) {
        MessageButtonCapabilities.Context context = new MessageButtonCapabilities.Context();
        context.filtering = true;
        context.provider = provider;
        context.protocol = EntityAccount.TYPE_IMAP;
        context.folderType = EntityFolder.INBOX;
        context.sourceWritable = true;
        context.hasUid = (provider == MessageButtonCapabilities.Provider.LEGACY);
        context.hasGmailId = (provider == MessageButtonCapabilities.Provider.GMAIL_API);
        context.hasGraphId = (provider == MessageButtonCapabilities.Provider.GRAPH_API);
        context.hasZohoId = (provider == MessageButtonCapabilities.Provider.ZOHO_API);
        context.gmailInInbox = true;
        context.hasTrashTarget = true;
        context.hasArchiveTarget = true;
        context.hasJunkTarget = true;
        context.hasMoveTarget = true;
        context.hasHeaders = true;
        context.hasRaw = false;
        return context;
    }

    private static boolean available(String preference, MessageButtonCapabilities.Context context) {
        return MessageButtonCapabilities.isAvailable(preference, context);
    }

    @Test
    public void nativeProviderMatrixShowsOnlyProviderSupportedServerButtons() {
        MessageButtonCapabilities.Context gmail = context(MessageButtonCapabilities.Provider.GMAIL_API);
        assertTrue(available("button_junk", gmail));
        assertTrue(available("button_trash", gmail));
        assertTrue(available("button_archive", gmail));
        assertTrue(available("button_move", gmail));
        assertTrue(available("button_labels", gmail));
        assertTrue(available("button_seen", gmail));
        assertTrue(available("button_importance", gmail));
        assertFalse(available("button_copy", gmail));
        assertFalse(available("button_keywords", gmail));
        assertFalse(available("button_raw", gmail));
        assertTrue("Local-only actions remain available across providers",
                available("button_notes", gmail));

        MessageButtonCapabilities.Context graph = context(MessageButtonCapabilities.Provider.GRAPH_API);
        assertTrue(available("button_junk", graph));
        assertTrue(available("button_trash", graph));
        assertTrue(available("button_archive", graph));
        assertTrue(available("button_move", graph));
        assertTrue(available("button_seen", graph));
        assertFalse(available("button_labels", graph));
        assertFalse(available("button_importance", graph));
        assertFalse(available("button_copy", graph));
        assertFalse(available("button_keywords", graph));
        assertFalse(available("button_raw", graph));
        graph.hasRaw = true;
        assertTrue("Graph raw-save is available only when original content was fetched", available("button_raw", graph));

        MessageButtonCapabilities.Context zoho = context(MessageButtonCapabilities.Provider.ZOHO_API);
        assertTrue(available("button_junk", zoho));
        assertTrue(available("button_trash", zoho));
        assertTrue(available("button_archive", zoho));
        assertTrue(available("button_move", zoho));
        assertTrue(available("button_seen", zoho));
        assertFalse(available("button_labels", zoho));
        assertFalse(available("button_importance", zoho));
        assertFalse(available("button_copy", zoho));
        assertFalse(available("button_keywords", zoho));
        assertFalse(available("button_raw", zoho));
    }

    @Test
    public void gmailButtonsFollowMessageAndSourceFolderSemantics() {
        MessageButtonCapabilities.Context gmail = context(MessageButtonCapabilities.Provider.GMAIL_API);
        gmail.inSent = true;
        gmail.folderType = EntityFolder.SENT;
        assertFalse("Sent-source moves are unsupported by the message picker", available("button_move", gmail));
        assertFalse("Gmail Archive requires an Inbox label and a non-Sent source", available("button_archive", gmail));
        assertFalse("Gmail Spam reporting excludes Sent messages", available("button_junk", gmail));
        assertTrue("The separate Gmail label toolbar action can still manage labels on Sent", available("button_labels", gmail));

        gmail = context(MessageButtonCapabilities.Provider.GMAIL_API);
        gmail.gmailDraft = true;
        gmail.folderType = EntityFolder.DRAFTS;
        assertFalse(available("button_junk", gmail));
        assertFalse(available("button_trash", gmail));
        assertFalse(available("button_archive", gmail));
        assertFalse(available("button_move", gmail));
        assertFalse(available("button_labels", gmail));

        gmail = context(MessageButtonCapabilities.Provider.GMAIL_API);
        gmail.inTrash = true;
        gmail.folderType = EntityFolder.TRASH;
        assertTrue("Gmail Trash exposes its permanent-delete button", available("button_trash", gmail));
        assertFalse(available("button_archive", gmail));
        assertFalse(available("button_labels", gmail));

        gmail = context(MessageButtonCapabilities.Provider.GMAIL_API);
        gmail.hasTrashTarget = false;
        gmail.hasArchiveTarget = false;
        gmail.hasJunkTarget = false;
        gmail.hasMoveTarget = false;
        assertFalse(available("button_trash", gmail));
        assertFalse(available("button_archive", gmail));
        assertFalse(available("button_junk", gmail));
        assertFalse(available("button_move", gmail));
        gmail.inTrash = true;
        gmail.folderType = EntityFolder.TRASH;
        assertTrue("Permanent delete in Trash does not require a separate Trash destination",
                available("button_trash", gmail));
    }

    @Test
    public void graphDeleteRequiresDeletedItemsUnlessAlreadyInThatFolder() {
        MessageButtonCapabilities.Context graph = context(MessageButtonCapabilities.Provider.GRAPH_API);
        graph.hasTrashTarget = false;
        assertFalse("Graph Delete outside Deleted Items needs a mapped Deleted Items destination",
                available("button_trash", graph));
        graph.inTrash = true;
        graph.folderType = EntityFolder.TRASH;
        assertTrue("Graph Delete from Deleted Items has its own supported route", available("button_trash", graph));
    }

    @Test
    public void gmailRestTrashActionIgnoresTheFoldersInformationalReadOnlyFlag() {
        EntityAccount account = new EntityAccount();
        account.id = 7L;
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;

        EntityFolder trash = new EntityFolder("Trash", EntityFolder.TRASH);
        trash.id = 70L;
        trash.account = account.id;
        trash.selectable = true;
        trash.read_only = true;
        trash.gmail_label_id = "TRASH";

        EntityMessage message = new EntityMessage();
        message.account = account.id;
        message.folder = trash.id;
        message.gmail_id = "gmail-message-id";

        MessageButtonCapabilities.Context capabilities = MessageButtonCapabilities.create(
                account, message, trash, Collections.singletonList(trash));
        assertTrue(capabilities.sourceWritable);
        assertTrue(capabilities.hasTrashTarget);
        assertTrue("Gmail REST labels use API writability, not the IMAP read_only marker",
                available("button_trash", capabilities));
    }

    @Test
    public void nativeMutationsRequireAValidWritableSourceAndMoveTarget() {
        for (MessageButtonCapabilities.Provider provider : new MessageButtonCapabilities.Provider[]{
                MessageButtonCapabilities.Provider.GMAIL_API,
                MessageButtonCapabilities.Provider.GRAPH_API,
                MessageButtonCapabilities.Provider.ZOHO_API}) {
            MessageButtonCapabilities.Context context = context(provider);
            context.sourceWritable = false;
            assertFalse(available("button_seen", context));
            assertFalse(available("button_trash", context));
            assertFalse(available("button_move", context));
            assertFalse(available("button_archive", context));
            context.sourceWritable = true;
            context.hasMoveTarget = false;
            assertFalse(available("button_move", context));

            context = context(provider);
            context.hasGmailId = false;
            context.hasGraphId = false;
            context.hasZohoId = false;
            context.hasUid = true;
            context.isPop = true;
            assertFalse("A native account with a missing provider ID must not fall through to legacy Junk",
                    available("button_junk", context));
            assertFalse(available("button_trash", context));
            assertFalse(available("button_archive", context));
            assertFalse(available("button_move", context));
            assertFalse(available("button_seen", context));
            assertFalse(available("button_importance", context));
        }

        MessageButtonCapabilities.Context zoho = context(MessageButtonCapabilities.Provider.ZOHO_API);
        zoho.folderType = EntityFolder.TRASH;
        zoho.inTrash = true;
        assertTrue("Zoho Delete remains available in Trash for its permanent-delete route",
                available("button_trash", zoho));
        zoho.folderType = EntityFolder.JUNK;
        zoho.inTrash = false;
        zoho.inJunk = true;
        assertTrue("Zoho Junk Delete remains available for its implemented permanent route",
                available("button_trash", zoho));
    }

    @Test
    public void legacyImapPopAndGmailHostImapKeepLegacyActionRoutes() {
        MessageButtonCapabilities.Context imap = context(MessageButtonCapabilities.Provider.LEGACY);
        assertTrue(available("button_seen", imap));
        assertTrue(available("button_importance", imap));
        assertTrue(available("button_junk", imap));
        assertTrue(available("button_trash", imap));
        assertTrue(available("button_archive", imap));
        assertTrue(available("button_move", imap));
        assertTrue(available("button_copy", imap));
        assertTrue(available("button_keywords", imap));
        assertFalse("Gmail-host IMAP is not the native Gmail label toolbar", available("button_labels", imap));

        MessageButtonCapabilities.Context pop = context(MessageButtonCapabilities.Provider.LEGACY);
        pop.protocol = EntityAccount.TYPE_POP;
        pop.isPop = true;
        pop.hasUid = false;
        pop.folderType = EntityFolder.INBOX;
        assertTrue(available("button_junk", pop));
        assertTrue(available("button_trash", pop));
        assertTrue(available("button_seen", pop));
        assertTrue(available("button_keywords", pop));
        assertFalse("POP without a UID has no folder-picker move route", available("button_move", pop));
        assertFalse(available("button_copy", pop));

        pop.folderType = EntityFolder.TRASH;
        assertFalse("POP sender blocking is an Inbox action", available("button_junk", pop));
    }

    @Test
    public void nonActionableHeadersRawUnsubscribeAndRulesAreFilteredPrecisely() {
        MessageButtonCapabilities.Context gmail = context(MessageButtonCapabilities.Provider.GMAIL_API);
        gmail.hasHeaders = false;
        gmail.hasUnsubscribe = false;
        assertFalse(available("button_headers", gmail));
        assertFalse(available("button_raw", gmail));
        assertFalse(available("button_unsubscribe", gmail));
        assertTrue(available("button_rule", gmail));
        gmail.outbox = true;
        assertFalse(available("button_rule", gmail));
    }

    @Test
    public void missingContextPreservesTheUnfilteredLegacyDialog() {
        assertTrue(available("button_copy", null));
        assertTrue(available("button_labels", MessageButtonCapabilities.Context.unfiltered()));

        MessageButtonCapabilities.Context unavailable = MessageButtonCapabilities.Context.unavailable();
        assertFalse("Unverified source context must not expose server-side copy", available("button_copy", unavailable));
        assertFalse("Unverified source context must not expose native Gmail labels", available("button_labels", unavailable));
        assertFalse("Unverified source context must not expose native delete", available("button_trash", unavailable));
        assertTrue("Local-only choices remain configurable", available("button_notes", unavailable));
    }
}
