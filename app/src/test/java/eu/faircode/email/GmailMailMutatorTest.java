package eu.faircode.email;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GmailMailMutatorTest {
    @Test
    public void longPressAndMoreMenuEnablementUseGmailApiIdOnly() {
        assertTrue(SelectionPredicateMessage.canSelectImapMessage(
                false, true, false, false, true));
        assertTrue(MessageSelectionCapabilities.canQueueNativeReadFlag(
                true, true, true, false, false, false, false));
        assertTrue(MessageSelectionCapabilities.canMoveToGmailFolder(
                true, false, true, true));
        assertFalse(MessageSelectionCapabilities.canMoveToGmailFolder(
                true, false, true, true, true));
        assertTrue(MessageSelectionCapabilities.canMoveFromGmailSource(false, true));
        assertTrue(MessageSelectionCapabilities.canTrashGmailMessage(
                true, true, true, false));
        assertFalse(MessageSelectionCapabilities.canMoveFromGmailSource(true, true));
        assertFalse(MessageSelectionCapabilities.canTrashGmailMessage(
                true, false, true, false));
        assertFalse(MessageSelectionCapabilities.canTrashGmailMessage(
                true, true, true, true));

        assertFalse(SelectionPredicateMessage.canSelectImapMessage(
                false, false, true, false, true));
        assertFalse(SelectionPredicateMessage.canSelectImapMessage(
                false, false, false, true, true));
        assertFalse(MessageSelectionCapabilities.canMoveToGmailFolder(
                true, true, true, true));
        assertFalse(MessageSelectionCapabilities.canMoveToGmailFolder(
                true, false, false, true));
        assertFalse(MessageSelectionCapabilities.canQueueNativeReadFlag(
                true, true, false, false, false, false, false));
    }

    @Test
    public void archiveRemovesOnlyInboxAndPreservesOtherGmailLabels() {
        List<String> current = Arrays.asList("INBOX", "STARRED", "Label_1", "CATEGORY_PERSONAL");
        List<String> archived = GmailMailMutator.applyLabelDelta(
                current, Collections.<String>emptyList(), Collections.singletonList("INBOX"));

        assertEquals(Arrays.asList("STARRED", "Label_1", "CATEGORY_PERSONAL"), archived);
    }

    @Test
    public void labelMoveChangesOnlyExplicitMemberships() {
        List<String> current = Arrays.asList("INBOX", "STARRED", "Label_Source", "CATEGORY_PERSONAL");
        List<String> moved = GmailMailMutator.applyLabelDelta(current,
                Collections.singletonList("Label_Target"),
                Arrays.asList("INBOX", "Label_Source"));

        assertEquals(Arrays.asList("STARRED", "CATEGORY_PERSONAL", "Label_Target"), moved);
        assertEquals(new HashSet<>(current), new HashSet<>(GmailMailMutator.rollbackLabelDelta(moved,
                Collections.singletonList("Label_Target"),
                Arrays.asList("INBOX", "Label_Source"))));
    }

    @Test
    public void movingFromInboxToUserLabelRemovesInboxAndAddsDestinationOnly() {
        List<String> current = Arrays.asList("INBOX", "STARRED", "Label_Keep", "CATEGORY_PERSONAL");
        GmailMailMutator.MovePlan move = GmailMailMutator.createMovePlan(
                EntityFolder.USER, "Label_Target", EntityFolder.INBOX,
                "INBOX", false, current);

        assertEquals(Collections.singletonList("Label_Target"), move.add);
        assertEquals(Collections.singletonList("INBOX"), move.remove);
        assertEquals(Arrays.asList("STARRED", "Label_Keep", "CATEGORY_PERSONAL", "Label_Target"),
                GmailMailMutator.applyLabelDelta(current, move.add, move.remove));
    }

    @Test
    public void sourceLabelFallsBackWhenMoveRequestHasNoLabelOrUsesArchiveViewId() {
        assertEquals("INBOX", GmailMailMutator.resolveMoveSourceLabel(null, "INBOX"));
        assertEquals("INBOX", GmailMailMutator.resolveMoveSourceLabel("", "INBOX"));
        assertEquals("INBOX", GmailMailMutator.resolveMoveSourceLabel("null", "INBOX"));
        assertEquals("INBOX", GmailMailMutator.resolveMoveSourceLabel(
                GmailLabelFolder.ARCHIVE_VIEW_ID, "INBOX"));
        assertEquals("Label_4", GmailMailMutator.resolveMoveSourceLabel("Label_4", "INBOX"));
    }

    @Test
    public void pickerMoveAllowsReadOnlyGmailSourceButKeepsOtherProvidersOnTheirOwnPath() {
        EntityFolder source = new EntityFolder("Inbox", EntityFolder.INBOX);
        source.account = 7L;
        source.read_only = true;
        source.selectable = true;

        EntityMessage gmailMessage = new EntityMessage();
        gmailMessage.account = 7L;
        gmailMessage.gmail_id = "gmail-id";

        EntityAccount gmail = new EntityAccount();
        gmail.id = 7L;
        gmail.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        gmail.protocol = EntityAccount.TYPE_IMAP;
        assertTrue(GmailMailMutator.canMoveFromSource(gmail, source, gmailMessage));

        EntityAccount graph = new EntityAccount();
        graph.id = 7L;
        graph.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        EntityMessage graphMessage = new EntityMessage();
        graphMessage.account = 7L;
        graphMessage.graph_id = "graph-id";
        assertFalse(GmailMailMutator.canMoveFromSource(graph, source, graphMessage));

        EntityAccount zoho = new EntityAccount();
        zoho.id = 7L;
        zoho.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        EntityMessage zohoMessage = new EntityMessage();
        zohoMessage.account = 7L;
        zohoMessage.zoho_id = "zoho-id";
        assertFalse(GmailMailMutator.canMoveFromSource(zoho, source, zohoMessage));

        EntityAccount imap = new EntityAccount();
        imap.id = 7L;
        imap.protocol = EntityAccount.TYPE_IMAP;
        assertFalse(GmailMailMutator.canMoveFromSource(imap, source, new EntityMessage()));
        source.read_only = false;
        assertTrue(GmailMailMutator.canMoveFromSource(imap, source, new EntityMessage()));
        assertFalse(GmailMailMutator.canMoveFromSource(gmail, source, new EntityMessage()));
    }

    @Test
    public void gmailMovePlansUseTrashAndUntrashEndpointsAndPreserveOtherLabels() {
        List<String> memberships = Arrays.asList("INBOX", "STARRED", "Label_Source", "CATEGORY_PERSONAL");

        GmailMailMutator.MovePlan archive = GmailMailMutator.createMovePlan(
                EntityFolder.ARCHIVE, "__gmail_archive__", EntityFolder.INBOX,
                "INBOX", false, memberships);
        assertFalse(archive.trash);
        assertFalse(archive.untrash);
        assertTrue(archive.add.isEmpty());
        assertEquals(Collections.singletonList("INBOX"), archive.remove);

        GmailMailMutator.MovePlan labelMove = GmailMailMutator.createMovePlan(
                EntityFolder.USER, "Label_Target", EntityFolder.USER,
                "Label_Source", true, memberships);
        assertFalse(labelMove.trash);
        assertFalse(labelMove.untrash);
        assertEquals(Collections.singletonList("Label_Target"), labelMove.add);
        assertEquals(Collections.singletonList("Label_Source"), labelMove.remove);

        GmailMailMutator.MovePlan labelToInbox = GmailMailMutator.createMovePlan(
                EntityFolder.INBOX, "INBOX", EntityFolder.USER,
                "Label_Source", true, Arrays.asList("Label_Source", "SENT", "STARRED"));
        assertEquals(Collections.singletonList("INBOX"), labelToInbox.add);
        assertEquals(Collections.singletonList("Label_Source"), labelToInbox.remove);

        GmailMailMutator.MovePlan trash = GmailMailMutator.createMovePlan(
                EntityFolder.TRASH, "TRASH", EntityFolder.USER,
                "Label_Source", true, memberships);
        assertTrue(trash.trash);
        assertTrue(trash.clearOtherLabels);
        assertFalse(trash.untrash);
        assertEquals(Collections.singletonList("TRASH"), trash.add);
        assertTrue(trash.remove.isEmpty());
        assertEquals(Arrays.asList("INBOX", "Label_Source", "STARRED"),
                GmailMailMutator.labelsToRemoveFromTrash(
                        Arrays.asList("TRASH", "SENT", "DRAFT", "INBOX", "Label_Source", "STARRED")));

        GmailMailMutator.MovePlan restore = GmailMailMutator.createMovePlan(
                EntityFolder.INBOX, "INBOX", EntityFolder.TRASH,
                "TRASH", false, Arrays.asList("TRASH", "STARRED", "Label_Source"));
        assertFalse(restore.trash);
        assertTrue(restore.untrash);
        assertEquals(Collections.singletonList("INBOX"), restore.add);
        assertTrue(restore.remove.isEmpty());

        GmailMailMutator.MovePlan restoreCustom = GmailMailMutator.createMovePlan(
                EntityFolder.USER, "Label_Target", EntityFolder.TRASH,
                "TRASH", false, Arrays.asList("TRASH", "SENT", "STARRED"));
        assertTrue(restoreCustom.untrash);
        assertEquals(Collections.singletonList("Label_Target"), restoreCustom.add);
        assertTrue(restoreCustom.remove.isEmpty());

        GmailMailMutator.MovePlan restoreArchive = GmailMailMutator.createMovePlan(
                EntityFolder.ARCHIVE, "__gmail_archive__", EntityFolder.TRASH,
                "TRASH", false, Arrays.asList("TRASH", "STARRED", "Label_Source"));
        assertTrue(restoreArchive.untrash);
        assertEquals(Collections.singletonList("INBOX"), restoreArchive.remove);

        GmailMailMutator.MovePlan restoreSpam = GmailMailMutator.createMovePlan(
                EntityFolder.INBOX, "INBOX", EntityFolder.JUNK,
                "SPAM", false, Arrays.asList("SPAM", "STARRED", "Label_Source"));
        assertFalse(restoreSpam.trash);
        assertFalse(restoreSpam.untrash);
        assertEquals(Collections.singletonList("INBOX"), restoreSpam.add);
        assertEquals(Collections.singletonList("SPAM"), restoreSpam.remove);

        GmailMailMutator.MovePlan moveOutOfSpam = GmailMailMutator.createMovePlan(
                EntityFolder.USER, "Label_Target", EntityFolder.JUNK,
                "SPAM", false, Arrays.asList("SPAM", "STARRED"));
        assertEquals(Collections.singletonList("Label_Target"), moveOutOfSpam.add);
        assertEquals(Collections.singletonList("SPAM"), moveOutOfSpam.remove);
    }

    @Test
    public void sentFolderRejectsMoveButLabelManagementAndTrashPreserveServerManagedSent() {
        List<String> sentOnly = Arrays.asList("SENT", "STARRED", "IMPORTANT", "CATEGORY_PERSONAL");

        boolean sentMoveRejected = false;
        try {
            GmailMailMutator.createMovePlan(
                    EntityFolder.USER, "Label_Target", EntityFolder.SENT, "SENT", false, sentOnly);
        } catch (IllegalArgumentException expected) {
            sentMoveRejected = true;
        }
        assertTrue("A generic Move from the Sent folder is unsupported", sentMoveRejected);

        GmailMailMutator.Plan addCustom = GmailMailMutator.createLabelPlan("Label_Target", true);
        assertEquals(Collections.singletonList("Label_Target"), addCustom.add);
        assertTrue(addCustom.remove.isEmpty());
        List<String> customLabels = GmailMailMutator.applyLabelDelta(
                sentOnly, addCustom.add, addCustom.remove);
        assertTrue(customLabels.contains("SENT"));
        assertTrue(customLabels.contains("Label_Target"));

        boolean managedSentRejected = false;
        try {
            GmailMailMutator.createLabelPlan("SENT", false);
        } catch (IllegalArgumentException expected) {
            managedSentRejected = true;
        }
        assertTrue("Gmail's server-managed SENT label cannot be removed", managedSentRejected);

        GmailMailMutator.MovePlan toTrash = GmailMailMutator.createMovePlan(
                EntityFolder.TRASH, "TRASH", EntityFolder.SENT, "SENT", false, sentOnly);
        assertTrue("Sent-to-Trash must use the dedicated messages.trash path", toTrash.trash);
        assertEquals(Collections.singletonList("TRASH"), toTrash.add);
        assertTrue(toTrash.remove.isEmpty());
    }

    @Test
    public void explicitPermanentDeleteIsGmailOnlyAndWorkerPreparesAnyNonDraftSource() {
        assertTrue(GmailMailMutator.canPermanentlyDelete(true, true,
                new String[]{"TRASH", "STARRED"}));
        assertTrue(GmailMailMutator.canPermanentlyDelete(true, true,
                new String[]{"INBOX", "STARRED"}));
        assertTrue(GmailMailMutator.canPermanentlyDelete(true, true,
                new String[]{"SPAM"}));
        assertFalse(GmailMailMutator.canPermanentlyDelete(true, true,
                new String[]{"DRAFT", "SENT"}));
        assertFalse(GmailMailMutator.canPermanentlyDelete(false, true,
                new String[]{"TRASH"}));
        assertFalse(GmailMailMutator.canPermanentlyDelete(true, false,
                new String[]{"TRASH"}));

        assertTrue(GmailMailMutator.canExpungeFolder(true, true,
                EntityFolder.TRASH, "TRASH"));
        assertFalse(GmailMailMutator.canExpungeFolder(true, true,
                EntityFolder.JUNK, "SPAM"));
        assertFalse(GmailMailMutator.canExpungeFolder(true, true,
                EntityFolder.USER, "Label_1"));
        assertFalse(GmailMailMutator.canExpungeFolder(false, true,
                EntityFolder.TRASH, "TRASH"));

        EntityAccount gmail = new EntityAccount();
        gmail.id = 7L;
        gmail.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        EntityAccount imap = new EntityAccount();
        imap.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        imap.protocol = EntityAccount.TYPE_IMAP;
        assertEquals(EntityOperation.GMAIL_PERMANENT_DELETE,
                GmailMailMutator.getDeleteOperation(gmail, true));
        assertEquals(EntityOperation.DELETE,
                GmailMailMutator.getDeleteOperation(gmail, false));
        assertEquals(EntityOperation.DELETE,
                GmailMailMutator.getDeleteOperation(imap, true));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.GMAIL_PERMANENT_DELETE));
        assertFalse(EntityOperation.isGraphApiMutation(EntityOperation.GMAIL_PERMANENT_DELETE));
        assertFalse(EntityOperation.isZohoApiMutation(EntityOperation.GMAIL_PERMANENT_DELETE));

        EntityMessage message = new EntityMessage();
        message.account = gmail.id;
        message.gmail_id = "gmail-id";
        EntityFolder inbox = new EntityFolder("Inbox", EntityFolder.INBOX);
        inbox.account = gmail.id;
        inbox.selectable = true;
        assertTrue(GmailMailMutator.canDeleteFromGmail(gmail, inbox, message));
        EntityFolder drafts = new EntityFolder("Drafts", EntityFolder.DRAFTS);
        drafts.account = gmail.id;
        drafts.selectable = true;
        assertFalse(GmailMailMutator.canDeleteFromGmail(gmail, drafts, message));
        message.labels = new String[]{"DRAFT", "SENT"};
        assertFalse(GmailMailMutator.canDeleteFromGmail(gmail, inbox, message));

        assertEquals(EntityOperation.EXPUNGE, GmailMailMutator.mapFolderOperation(
                true, true, EntityFolder.TRASH, "TRASH", EntityOperation.PURGE));
        assertEquals(EntityOperation.EXPUNGE, GmailMailMutator.mapFolderOperation(
                true, true, EntityFolder.TRASH, "TRASH", EntityOperation.EXPUNGE));
        assertNull(GmailMailMutator.mapFolderOperation(
                true, true, EntityFolder.JUNK, "SPAM", EntityOperation.PURGE));
        assertNull(GmailMailMutator.mapFolderOperation(
                true, true, EntityFolder.USER, "Label_1", EntityOperation.PURGE));
        assertEquals(EntityOperation.PURGE, GmailMailMutator.mapFolderOperation(
                false, true, EntityFolder.JUNK, null, EntityOperation.PURGE));
    }

    @Test
    public void ordinaryGmailDeleteFromInboxOrSpamRoutesToTrashNotPermanentDelete() {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        account.protocol = EntityAccount.TYPE_IMAP;
        account.id = 7L;
        EntityMessage message = new EntityMessage();
        message.account = account.id;
        message.gmail_id = "gmail-id";
        message.labels = new String[]{"INBOX", "Label_1"};
        EntityFolder inbox = new EntityFolder("Inbox", EntityFolder.INBOX);
        inbox.account = account.id;
        EntityFolder spam = new EntityFolder("Spam", EntityFolder.JUNK);
        spam.account = account.id;
        EntityFolder trash = new EntityFolder("Trash", EntityFolder.TRASH);
        trash.account = account.id;
        EntityFolder sent = new EntityFolder("Sent", EntityFolder.SENT);
        sent.account = account.id;
        EntityFolder custom = customFolder(account, "Label_Custom");

        assertTrue(GmailMailMutator.shouldRouteDeleteToTrash(account, inbox, message));
        assertTrue(GmailMailMutator.shouldRouteDeleteToTrash(account, spam, message));
        assertTrue(GmailMailMutator.shouldRouteDeleteToTrash(account, sent, message));
        assertTrue(GmailMailMutator.shouldRouteDeleteToTrash(account, custom, message));
        message.labels = new String[]{"SENT", "Label_1"};
        assertTrue(GmailMailMutator.isWritableMessage(account, sent, message));
        assertTrue(GmailMailMutator.shouldRouteDeleteToTrash(account, sent, message));
        assertFalse(GmailMailMutator.shouldRouteDeleteToTrash(account, trash, message));
        assertTrue(GmailMailMutator.canPermanentlyDelete(true, true, message.labels));
        assertTrue(GmailMailMutator.canPermanentlyDelete(true, true, new String[]{"SPAM"}));
        message.labels = new String[]{"TRASH", "Label_1"};
        assertFalse("A stale physical folder must not override Gmail Trash membership",
                GmailMailMutator.shouldRouteDeleteToTrash(account, inbox, message));
        assertTrue(GmailMailMutator.canPermanentlyDelete(true, true,
                message.labels));
    }

    @Test
    public void serverLabelsReconcileReadStarAndTrashStateAndPreserveMemberships() {
        GmailMailMutator.LocalState state = GmailMailMutator.localStateFromLabels(
                Arrays.asList("TRASH", "STARRED", "Label_1"));

        assertTrue(state.seen);
        assertTrue(state.flagged);
        assertTrue(state.trashed);
        assertEquals(Arrays.asList("TRASH", "STARRED", "Label_1"), state.labels);

        GmailMailMutator.LocalState unread = GmailMailMutator.localStateFromLabels(
                Arrays.asList("INBOX", "UNREAD", "Label_1"));
        assertFalse(unread.seen);
        assertFalse(unread.flagged);
        assertFalse(unread.trashed);
        assertEquals(Arrays.asList("INBOX", "UNREAD", "Label_1"), unread.labels);
    }

    @Test
    public void gmailLabelSnapshotProjectsEveryServerDerivedMessageChip() {
        GmailMessageLabelState state = GmailMessageLabelState.from(Arrays.asList(
                "TRASH", "UNREAD", "STARRED", "IMPORTANT", "ANSWERED", "Label_7"));

        assertEquals(Arrays.asList("TRASH", "UNREAD", "STARRED", "IMPORTANT", "ANSWERED", "Label_7"),
                state.labels);
        assertFalse(state.seen);
        assertTrue(state.flagged);
        assertTrue(state.deleted);
        assertTrue(state.answered);
        assertEquals(Integer.valueOf(EntityMessage.PRIORITIY_HIGH), state.importance);

        GmailMessageLabelState cleared = GmailMessageLabelState.from(
                Arrays.asList("INBOX", "Label_7"));
        assertTrue(cleared.seen);
        assertFalse(cleared.flagged);
        assertFalse(cleared.deleted);
        assertFalse(cleared.answered);
        assertNull(cleared.importance);
    }

    @Test
    public void failedActionIsResolvedOnlyWhenServerSnapshotSatisfiesItsPlan() {
        EntityOperation failed = new EntityOperation();
        failed.error = "Previous Gmail REST write failed";
        GmailMailMutator.Plan seen = GmailMailMutator.createSeenPlan(true);

        assertTrue(GmailMailMutator.isFailedOperationSatisfied(failed, seen,
                Arrays.asList("INBOX", "STARRED")));
        assertFalse(GmailMailMutator.isFailedOperationSatisfied(failed, seen,
                Arrays.asList("INBOX", "UNREAD", "STARRED")));

        failed.error = null;
        assertFalse(GmailMailMutator.isFailedOperationSatisfied(failed, seen,
                Arrays.asList("INBOX", "STARRED")));
    }

    @Test
    public void unsupportedGmailActionsStayDisabledWithoutChangingGraphZohoOrImapRouting() {
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.SEEN));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.FLAG));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.LABEL));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.KEYWORD));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.MOVE));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.GMAIL_PERMANENT_DELETE));
        assertFalse(EntityOperation.isGmailApiMutation(EntityOperation.ANSWERED));
        assertFalse(EntityOperation.isGmailApiMutation(EntityOperation.COPY));
        assertFalse(EntityOperation.isGmailApiMutation(EntityOperation.ADD));
        assertFalse(EntityOperation.isGmailApiMutation(EntityOperation.EXPUNGE));
        assertTrue(EntityOperation.isGmailApiWriteOperation(EntityOperation.EXPUNGE));
        assertTrue(EntityOperation.isGraphApiMutation(EntityOperation.COPY));
        assertFalse(EntityOperation.isGraphApiMutation(EntityOperation.GMAIL_PERMANENT_DELETE));
        assertTrue(EntityOperation.isZohoApiMutation(EntityOperation.ANSWERED));
        assertFalse(EntityOperation.isZohoApiMutation(EntityOperation.GMAIL_PERMANENT_DELETE));
        assertFalse(EntityOperation.isGmailApiMutation(EntityOperation.SEND));
    }

    @Test
    public void permanentDeleteAlwaysRequiresConfirmationEvenWhenOrdinaryDeleteOptedOut() {
        assertFalse(GmailMailMutator.shouldSkipSingleDeleteConfirmation(false, false));
        assertTrue(GmailMailMutator.shouldSkipSingleDeleteConfirmation(false, true));
        assertFalse(GmailMailMutator.shouldSkipSingleDeleteConfirmation(true, false));
        assertFalse(GmailMailMutator.shouldSkipSingleDeleteConfirmation(true, true));

        EntityAccount gmail = new EntityAccount();
        gmail.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        gmail.protocol = EntityAccount.TYPE_IMAP;
        assertEquals(EntityOperation.DELETE, GmailMailMutator.getDeleteOperation(gmail, false));
        assertEquals(EntityOperation.GMAIL_PERMANENT_DELETE,
                GmailMailMutator.getDeleteOperation(gmail, true));
        EntityAccount imap = new EntityAccount();
        imap.protocol = EntityAccount.TYPE_IMAP;
        assertEquals(EntityOperation.DELETE, GmailMailMutator.getDeleteOperation(imap, true));

        assertFalse(MessageSelectionCapabilities.shouldShowPermanentDeleteConfirmation(false, false));
        assertTrue(MessageSelectionCapabilities.shouldShowPermanentDeleteConfirmation(false, true));
        assertTrue(MessageSelectionCapabilities.shouldShowPermanentDeleteConfirmation(true, false));
    }

    @Test
    public void uidlessDeleteRoutingKeepsGmailOnRestWithoutChangingOtherProviders() {
        assertFalse(MessageSelectionCapabilities.shouldDeleteLocallyWithoutUid(
                true, false, false, EntityAccount.TYPE_IMAP, false));
        assertTrue(MessageSelectionCapabilities.shouldDeleteLocallyWithoutUid(
                false, false, false, EntityAccount.TYPE_IMAP, false));
        assertFalse(MessageSelectionCapabilities.shouldDeleteLocallyWithoutUid(
                false, true, false, EntityAccount.TYPE_IMAP, false));
        assertFalse(MessageSelectionCapabilities.shouldDeleteLocallyWithoutUid(
                false, false, false, EntityAccount.TYPE_IMAP, true));
        assertFalse(MessageSelectionCapabilities.shouldDeleteLocallyWithoutUid(
                false, false, false, EntityAccount.TYPE_POP, false));
    }

    @Test
    public void staleGmailPickerCallbackRequiresVisibleRefreshNotFakeSuccess() {
        assertEquals(GmailMailMutator.CALLBACK_REFRESH,
                GmailMailMutator.classifyCallbackResult(true, false, false));
        assertEquals(GmailMailMutator.CALLBACK_REFRESH,
                GmailMailMutator.classifyCallbackResult(true, true, false));
        assertEquals(GmailMailMutator.CALLBACK_QUEUED,
                GmailMailMutator.classifyCallbackResult(true, true, true));
        assertEquals(GmailMailMutator.CALLBACK_LEGACY,
                GmailMailMutator.classifyCallbackResult(false, false, false));
    }

    @Test
    public void allMailIsNotAMoveDestinationAndArchiveRequiresInboxMembership() {
        EntityAccount gmail = new EntityAccount();
        gmail.id = 19L;
        gmail.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        gmail.protocol = EntityAccount.TYPE_IMAP;

        EntityFolder allMail = new EntityFolder("All Mail", EntityFolder.ARCHIVE);
        allMail.account = gmail.id;
        allMail.selectable = true;
        allMail.gmail_label_id = EntityFolder.GMAIL_API_ALL_MAIL_VIEW_ID;
        assertTrue(GmailMailMutator.isValidTarget(gmail, allMail));
        assertFalse(GmailMailMutator.isValidMovePickerTarget(gmail, allMail));
        assertFalse(GmailMailMutator.isValidGmailMovePickerTarget(
                EntityFolder.ARCHIVE, EntityFolder.GMAIL_API_ALL_MAIL_VIEW_ID));

        EntityFolder inbox = new EntityFolder("Inbox", EntityFolder.INBOX);
        inbox.account = gmail.id;
        inbox.selectable = true;
        inbox.gmail_label_id = "INBOX";
        assertTrue(GmailMailMutator.isValidMovePickerTarget(gmail, inbox));

        EntityFolder spam = new EntityFolder("Spam", EntityFolder.JUNK);
        spam.account = gmail.id;
        spam.selectable = true;
        spam.gmail_label_id = "SPAM";
        assertTrue("Gmail SPAM is a manually applicable system label",
                GmailMailMutator.isValidMovePickerTarget(gmail, spam));

        EntityFolder sent = new EntityFolder("Sent", EntityFolder.SENT);
        sent.account = gmail.id;
        sent.selectable = true;
        sent.gmail_label_id = "SENT";
        assertFalse("Gmail SENT is automatically managed, not a writable destination",
                GmailMailMutator.isValidMovePickerTarget(gmail, sent));

        EntityFolder drafts = new EntityFolder("Drafts", EntityFolder.DRAFTS);
        drafts.account = gmail.id;
        drafts.selectable = true;
        drafts.gmail_label_id = "DRAFT";
        assertFalse(GmailMailMutator.isValidMovePickerTarget(gmail, drafts));
        assertFalse(GmailMailMutator.isValidTarget(gmail, drafts));

        EntityFolder writableLabel = customFolder(gmail, "Label_Writable");
        assertTrue(GmailMailMutator.isValidMovePickerTarget(gmail, writableLabel));
        assertTrue(GmailMailMutator.isWritableUserLabelTarget(writableLabel, gmail.id));
        EntityFolder readOnlyLabel = customFolder(gmail, "Label_ReadOnly");
        readOnlyLabel.read_only = true;
        assertTrue("Gmail custom labels are written via REST despite the IMAP read-only flag",
                GmailMailMutator.isValidMovePickerTarget(gmail, readOnlyLabel));
        assertTrue("The quick-action scan must include REST-valid labels despite the legacy marker",
                GmailMailMutator.hasValidMovePickerTarget(gmail, Collections.singletonList(readOnlyLabel)));
        assertTrue(MessageSelectionCapabilities.canMoveToGmailFolder(
                true, false, true,
                GmailMailMutator.hasValidMovePickerTarget(gmail, Collections.singletonList(readOnlyLabel)),
                false));
        assertFalse("All-Sent selections remain subject to the Sent move restriction",
                MessageSelectionCapabilities.canMoveToGmailFolder(
                        true, false, true,
                        GmailMailMutator.hasValidMovePickerTarget(gmail, Collections.singletonList(readOnlyLabel)),
                        true));
        assertTrue(GmailMailMutator.isWritableUserLabelTarget(readOnlyLabel, gmail.id));

        EntityFolder notifications = customFolder(gmail, "Label_Notifications");
        assertFalse("A custom Notifications view must not inherit the Sent-only restriction",
                GmailMailMutator.isSentSource(notifications));
        EntityMessage notificationMessage = new EntityMessage();
        notificationMessage.account = gmail.id;
        notificationMessage.gmail_id = "notification-message";
        notificationMessage.labels = new String[]{"INBOX", "Label_Notifications"};
        assertTrue(GmailMailMutator.canMoveToTarget(
                gmail, notifications, notificationMessage, inbox, "Label_Notifications"));
        assertTrue(GmailMailMutator.canMoveToTarget(
                gmail, notifications, notificationMessage, spam, "Label_Notifications"));

        EntityFolder foreignLabel = customFolder(gmail, "Label_Foreign");
        foreignLabel.account = gmail.id + 1;
        assertFalse(GmailMailMutator.isValidMovePickerTarget(gmail, foreignLabel));
        assertFalse(GmailMailMutator.isWritableUserLabelTarget(foreignLabel, gmail.id));

        GmailMailMutator.MovePlan spamPlan = GmailMailMutator.createMovePlan(
                EntityFolder.JUNK, "SPAM", EntityFolder.INBOX, "INBOX", false,
                Arrays.asList("INBOX", "Label_1"));
        assertEquals(Arrays.asList("SPAM"), spamPlan.add);
        assertEquals(Arrays.asList("INBOX"), spamPlan.remove);

        EntityMessage message = new EntityMessage();
        message.account = gmail.id;
        message.gmail_id = "gmail-id";
        message.labels = new String[]{"INBOX", "Label_1"};
        assertTrue(GmailMailMutator.canArchiveFromGmail(gmail, inbox, message));
        assertTrue(GmailMailMutator.canMoveToTarget(gmail, inbox, message, allMail));
        assertTrue(GmailMailMutator.canMoveToTarget(gmail, inbox, message, spam));

        assertTrue(GmailMailMutator.isManuallyApplicableSystemLabelId("INBOX"));
        assertTrue(GmailMailMutator.isManuallyApplicableSystemLabelId("TRASH"));
        assertTrue(GmailMailMutator.isManuallyApplicableSystemLabelId("SPAM"));
        assertTrue(GmailMailMutator.isManuallyApplicableSystemLabelId("CATEGORY_PERSONAL"));
        assertTrue(GmailMailMutator.isManuallyApplicableSystemLabelId("CATEGORY_FORUMS"));
        assertFalse(GmailMailMutator.isManuallyApplicableSystemLabelId("SENT"));
        assertFalse(GmailMailMutator.isManuallyApplicableSystemLabelId("DRAFT"));
        assertFalse(GmailMailMutator.isManuallyApplicableSystemLabelId(
                EntityFolder.GMAIL_API_ALL_MAIL_VIEW_ID));

        message.labels = new String[]{"Label_1"};
        assertFalse(GmailMailMutator.canMoveToTarget(gmail, customFolder(gmail, "Label_1"), message, allMail));

        EntityFolder sentSource = new EntityFolder("Sent", EntityFolder.SENT);
        sentSource.account = gmail.id;
        sentSource.selectable = true;
        sentSource.gmail_label_id = "SENT";
        message.labels = new String[]{"SENT"};
        assertFalse(GmailMailMutator.canMoveToTarget(gmail, sentSource, message, spam));
        assertFalse(GmailMailMutator.canMoveToTarget(gmail, sentSource, message, inbox));
        assertFalse(GmailMailMutator.canMoveToTarget(
                gmail, sentSource, message, customFolder(gmail, "Label_Target")));
        assertTrue("Sent remains eligible for label management", GmailMailMutator.isValidMessageLabelTarget(message));
        GmailMailMutator.Plan addLabel = GmailMailMutator.createLabelPlan("Label_Target", true);
        assertEquals(Collections.singletonList("Label_Target"), addLabel.add);
        assertTrue(addLabel.remove.isEmpty());
        assertEquals(Arrays.asList("SENT", "Label_Target"), GmailMailMutator.applyLabelDelta(
                Collections.singletonList("SENT"), addLabel.add, addLabel.remove));
        GmailMailMutator.Plan removeLabel = GmailMailMutator.createLabelPlan("Label_Target", false);
        assertEquals(Collections.singletonList("Label_Target"), removeLabel.remove);
        assertEquals(Collections.singletonList("SENT"), GmailMailMutator.applyLabelDelta(
                Arrays.asList("SENT", "Label_Target"), removeLabel.add, removeLabel.remove));
        EntityFolder sentTrash = new EntityFolder("Trash", EntityFolder.TRASH);
        sentTrash.account = gmail.id;
        sentTrash.selectable = true;
        sentTrash.gmail_label_id = "TRASH";
        assertTrue("Sent messages remain eligible for Trash", GmailMailMutator.canMoveToTarget(
                gmail, sentSource, message, sentTrash));
        assertEquals(GmailMailMutator.MoveCallbackValidation.INVALID_TARGET,
                GmailMailMutator.validateMoveCallback(gmail, sentSource, message,
                        customFolder(gmail, "Label_Target")));
        assertEquals(GmailMailMutator.MoveCallbackValidation.READY,
                GmailMailMutator.validateMoveCallback(gmail, sentSource, message, sentTrash));
        EntityFolder sourceCustom = customFolder(gmail, "Label_Source");
        message.labels = new String[]{"SENT", "Label_Source"};
        assertEquals(GmailMailMutator.MoveCallbackValidation.READY,
                GmailMailMutator.validateMoveCallback(gmail, sentSource, message,
                        customFolder(gmail, "Label_Target"), sourceCustom));
        GmailMailMutator.MovePlan fromCustomView = GmailMailMutator.createMovePlan(
                EntityFolder.USER, "Label_Target", EntityFolder.SENT, "Label_Source", true,
                Arrays.asList("SENT", "Label_Source"));
        assertEquals(Collections.singletonList("Label_Target"), fromCustomView.add);
        assertEquals(Collections.singletonList("Label_Source"), fromCustomView.remove);
        assertEquals(Arrays.asList("SENT", "Label_Target"), GmailMailMutator.applyLabelDelta(
                Arrays.asList("SENT", "Label_Source"), fromCustomView.add, fromCustomView.remove));

        message.labels = new String[]{"DRAFT"};
        assertFalse(GmailMailMutator.canMoveToTarget(gmail, inbox, message, spam));

        EntityFolder custom = new EntityFolder("Custom", EntityFolder.USER);
        custom.account = gmail.id;
        custom.selectable = true;
        custom.gmail_label_id = "Label_1";
        message.labels = new String[]{"Label_1"};
        assertFalse(GmailMailMutator.canArchiveFromGmail(gmail, custom, message));

        message.labels = new String[]{"DRAFT", "INBOX"};
        assertFalse(GmailMailMutator.canArchiveFromGmail(gmail, inbox, message));

        EntityAccount graph = new EntityAccount();
        graph.id = gmail.id;
        graph.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        assertFalse(GmailMailMutator.isValidMovePickerTarget(graph, inbox));
        assertFalse(GmailMailMutator.canArchiveFromGmail(graph, inbox, message));
    }

    @Test
    public void missingGmailMoveSourceRequiresVisibleRefreshInsteadOfQueueSuccess() {
        EntityAccount gmail = new EntityAccount();
        gmail.id = 19L;
        gmail.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        gmail.protocol = EntityAccount.TYPE_IMAP;

        EntityFolder inbox = new EntityFolder("Inbox", EntityFolder.INBOX);
        inbox.account = gmail.id;
        inbox.selectable = true;
        inbox.gmail_label_id = "INBOX";

        assertEquals(GmailMailMutator.MoveCallbackValidation.SOURCE_MISSING,
                GmailMailMutator.validateMoveCallback(gmail, inbox, null, inbox));

        EntityMessage message = new EntityMessage();
        message.account = gmail.id;
        message.folder = 10L;
        message.gmail_id = "opaque-test-id";
        message.labels = new String[]{"INBOX"};
        assertEquals(GmailMailMutator.MoveCallbackValidation.INVALID_TARGET,
                GmailMailMutator.validateMoveCallback(gmail, inbox, message, null));
    }

    @Test
    public void inboxMoveAndInboxOrSentTrashPassGmailCallbackAndQueueRouting() {
        EntityAccount gmail = new EntityAccount();
        gmail.id = 19L;
        gmail.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        gmail.protocol = EntityAccount.TYPE_IMAP;

        EntityFolder inbox = new EntityFolder("Inbox", EntityFolder.INBOX);
        inbox.id = 30L;
        inbox.account = gmail.id;
        inbox.selectable = true;
        inbox.read_only = true;
        inbox.gmail_label_id = "INBOX";

        EntityFolder custom = customFolder(gmail, "Label_Target");
        custom.id = 31L;
        custom.read_only = true;

        EntityMessage inboxMessage = new EntityMessage();
        inboxMessage.account = gmail.id;
        inboxMessage.folder = inbox.id;
        inboxMessage.gmail_id = "inbox-gmail-id";
        inboxMessage.labels = new String[]{"INBOX", "STARRED", "Label_Keep"};

        assertTrue("Gmail REST eligibility must not inherit the virtual folder IMAP read-only flag",
                GmailMailMutator.isWritableMessage(gmail, inbox, inboxMessage));
        assertEquals(GmailMailMutator.MoveCallbackValidation.READY,
                GmailMailMutator.validateMoveCallback(gmail, inbox, inboxMessage, custom));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.MOVE));
        org.json.JSONArray moveArgs = EntityOperation.createOperationArgs(
                custom.id, null, null, true, null, "INBOX");
        String sourceLabel = GmailMailMutator.resolveMoveSourceLabelFromOperationArgs(
                moveArgs, inbox.gmail_label_id);
        GmailMailMutator.MovePlan move = GmailMailMutator.createMovePlan(
                EntityFolder.USER, custom.gmail_label_id, EntityFolder.INBOX, sourceLabel, false,
                Arrays.asList("INBOX", "STARRED", "Label_Keep"));
        assertEquals(Collections.singletonList("Label_Target"), move.add);
        assertEquals(Collections.singletonList("INBOX"), move.remove);

        EntityFolder sent = new EntityFolder("Sent", EntityFolder.SENT);
        sent.id = 32L;
        sent.account = gmail.id;
        sent.selectable = true;
        sent.read_only = true;
        sent.gmail_label_id = "SENT";
        for (EntityFolder source : Arrays.asList(inbox, sent)) {
            EntityMessage message = new EntityMessage();
            message.account = gmail.id;
            message.folder = source.id;
            message.gmail_id = "gmail-message-id";
            message.labels = new String[]{source.gmail_label_id};
            assertTrue(GmailMailMutator.canDeleteFromGmail(gmail, source, message));
            assertEquals(EntityOperation.DELETE, GmailMailMutator.getDeleteOperation(gmail, false));
            assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.DELETE));
        }
        GmailMailMutator.Plan trash = GmailMailMutator.createTrashPlan();
        assertTrue(trash.trash);
        assertTrue(trash.clearOtherLabels);
    }

    @Test
    public void sentMoveCallbackRejectsMoveDestinationsButAllowsTrashAndRejectsDrafts() {
        EntityAccount gmail = new EntityAccount();
        gmail.id = 19L;
        gmail.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        gmail.protocol = EntityAccount.TYPE_IMAP;

        EntityFolder sent = new EntityFolder("Sent", EntityFolder.SENT);
        sent.account = gmail.id;
        sent.selectable = true;
        sent.gmail_label_id = "SENT";
        sent.read_only = true;

        EntityMessage message = new EntityMessage();
        message.account = gmail.id;
        message.folder = 30L;
        message.gmail_id = "sent-message-id";
        message.labels = new String[]{"SENT", "STARRED", "CATEGORY_PERSONAL"};

        EntityFolder inbox = new EntityFolder("Inbox", EntityFolder.INBOX);
        inbox.account = gmail.id;
        inbox.selectable = true;
        inbox.gmail_label_id = "INBOX";
        EntityFolder custom = customFolder(gmail, "Label_Target");
        EntityFolder spam = new EntityFolder("Spam", EntityFolder.JUNK);
        spam.account = gmail.id;
        spam.selectable = true;
        spam.gmail_label_id = "SPAM";
        EntityFolder trash = new EntityFolder("Trash", EntityFolder.TRASH);
        trash.account = gmail.id;
        trash.selectable = true;
        trash.gmail_label_id = "TRASH";
        EntityFolder sentTarget = new EntityFolder("Sent", EntityFolder.SENT);
        sentTarget.account = gmail.id;
        sentTarget.selectable = true;
        sentTarget.gmail_label_id = "SENT";
        EntityFolder drafts = new EntityFolder("Drafts", EntityFolder.DRAFTS);
        drafts.account = gmail.id;
        drafts.selectable = true;
        drafts.gmail_label_id = "DRAFT";

        assertEquals(GmailMailMutator.MoveCallbackValidation.INVALID_TARGET,
                GmailMailMutator.validateMoveCallback(gmail, sent, message, inbox));
        assertEquals(GmailMailMutator.MoveCallbackValidation.INVALID_TARGET,
                GmailMailMutator.validateMoveCallback(gmail, sent, message, custom));
        assertEquals(GmailMailMutator.MoveCallbackValidation.INVALID_TARGET,
                GmailMailMutator.validateMoveCallback(gmail, sent, message, spam));
        assertEquals(GmailMailMutator.MoveCallbackValidation.READY,
                GmailMailMutator.validateMoveCallback(gmail, sent, message, trash));
        EntityFolder unifiedSent = new EntityFolder("Sent", EntityFolder.SENT);
        unifiedSent.unified = true;
        assertEquals("SENT", GmailMailMutator.resolveMoveSourceViewLabel(gmail, sent, unifiedSent));
        assertEquals(GmailMailMutator.MoveCallbackValidation.INVALID_TARGET,
                GmailMailMutator.validateMoveCallback(gmail, sent, message, custom, unifiedSent));
        assertEquals(GmailMailMutator.MoveCallbackValidation.READY,
                GmailMailMutator.validateMoveCallback(gmail, sent, message, trash, unifiedSent));
        assertEquals(GmailMailMutator.MoveCallbackValidation.INVALID_TARGET,
                GmailMailMutator.validateMoveCallback(gmail, sent, message, sentTarget));
        assertEquals(GmailMailMutator.MoveCallbackValidation.INVALID_TARGET,
                GmailMailMutator.validateMoveCallback(gmail, sent, message, drafts));
        message.labels = new String[]{"DRAFT", "SENT"};
        assertEquals(GmailMailMutator.MoveCallbackValidation.INVALID_TARGET,
                GmailMailMutator.validateMoveCallback(gmail, sent, message, trash));
    }

    private EntityFolder customFolder(EntityAccount account, String labelId) {
        EntityFolder folder = new EntityFolder("Custom", EntityFolder.USER);
        folder.account = account.id;
        folder.selectable = true;
        folder.gmail_label_id = labelId;
        return folder;
    }
}
