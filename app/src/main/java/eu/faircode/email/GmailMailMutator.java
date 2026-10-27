/*
    This file is part of FairEmail.
    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
    See the GNU General Public License for more details.
    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/
package eu.faircode.email;

import android.content.Context;
import android.text.TextUtils;
import android.os.OperationCanceledException;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Gmail API-only queued message mutations. No IMAP commands or provider IDs are used here. */
final class GmailMailMutator {
    private static final String UNREAD = "UNREAD";
    private static final String STARRED = "STARRED";
    private static final String IMPORTANT = "IMPORTANT";
    private static final String SENT = "SENT";
    private static final String SPAM = "SPAM";
    private static final String TRASH = "TRASH";
    private static final String DRAFT = "DRAFT";
    private static final String EXECUTING = "executing";
    private static final int MAX_LABELS_PER_MODIFY = 100;
    private static final int MAX_TRASH_CLEANUP_PASSES = 100;
    static final int CALLBACK_LEGACY = 0;
    static final int CALLBACK_QUEUED = 1;
    static final int CALLBACK_REFRESH = 2;

    enum MoveCallbackValidation {
        READY,
        SOURCE_MISSING,
        INVALID_TARGET
    }

    private GmailMailMutator() {
    }

    static boolean isWritableMessage(EntityAccount account, EntityFolder folder,
                                     EntityMessage message) {
        return (account != null && account.isGmailApi() && folder != null && message != null &&
                folder.account != null && account.id == folder.account && account.id == message.account &&
                folder.selectable && message.gmail_id != null && message.gmail_id.length() > 0);
    }

    static int classifyCallbackResult(boolean gmailApi, boolean sourceRowPresent,
                                      boolean durableOperationQueued) {
        if (!gmailApi)
            return CALLBACK_LEGACY;
        return (sourceRowPresent && durableOperationQueued
                ? CALLBACK_QUEUED : CALLBACK_REFRESH);
    }

    static boolean canMoveFromSource(EntityAccount account, EntityFolder folder,
                                     EntityMessage message) {
        if (account == null || folder == null)
            return false;
        if (account.isGmailApi())
            return canDeleteFromGmail(account, folder, message);
        return !folder.read_only;
    }

    static boolean canMoveToTarget(EntityAccount account, EntityFolder source,
                                   EntityMessage message, EntityFolder target) {
        return canMoveToTarget(account, source, message, target,
                source == null ? null : source.gmail_label_id);
    }

    static boolean canMoveToTarget(EntityAccount account, EntityFolder source,
                                   EntityMessage message, EntityFolder target, String sourceLabel) {
        if (!canMoveFromSource(account, source, message) || !isValidTarget(account, target) ||
                !Objects.equals(source.account, target.account))
            return false;
        boolean sentSource = (sourceLabel == null ? isSentSource(source) : SENT.equals(sourceLabel));
        if (sentSource && !EntityFolder.TRASH.equals(target.type))
            return false;
        if (EntityFolder.ARCHIVE.equals(target.type))
            return canArchiveFromGmail(account, source, message);
        if (EntityFolder.JUNK.equals(target.type))
            return true;
        return true;
    }
    static boolean isSentSource(EntityFolder source) {
        return (source != null && (EntityFolder.SENT.equals(source.type) ||
                SENT.equals(source.gmail_label_id)));
    }

    static String resolveMoveSourceViewLabel(EntityAccount account, EntityFolder source,
                                             EntityFolder sourceView) {
        if (account == null || sourceView == null)
            return null;
        if (!Objects.equals(sourceView.account, account.id) &&
                !(sourceView.account == null && Boolean.TRUE.equals(sourceView.unified)))
            return null;
        if (sourceView.gmail_label_id != null && !sourceView.gmail_label_id.isEmpty())
            return sourceView.gmail_label_id;
        if (EntityFolder.INBOX.equals(sourceView.type))
            return "INBOX";
        if (EntityFolder.TRASH.equals(sourceView.type))
            return TRASH;
        if (EntityFolder.JUNK.equals(sourceView.type))
            return SPAM;
        if (EntityFolder.SENT.equals(sourceView.type))
            return SENT;
        if (EntityFolder.ARCHIVE.equals(sourceView.type))
            return GmailLabelFolder.ALL_MAIL_VIEW_ID;
        return (source == null ? null : source.gmail_label_id);
    }

    static boolean canPermanentlyDelete(boolean gmailApiAccount, boolean hasGmailId,
                                        String[] labels) {
        // Trash is prepared and verified by the worker; a draft cannot be prepared with messages.modify.
        return (gmailApiAccount && hasGmailId && !GmailLabelFolder.hasLabel(labels, DRAFT));
    }

    static boolean canDeleteFromGmail(EntityAccount account, EntityFolder folder,
                                      EntityMessage message) {
        return (isWritableMessage(account, folder, message) &&
                !EntityFolder.DRAFTS.equals(folder.type) &&
                !GmailLabelFolder.hasLabel(message.labels, DRAFT));
    }

    static boolean canArchiveFromGmail(EntityAccount account, EntityFolder folder,
                                       EntityMessage message) {
        return (isWritableMessage(account, folder, message) &&
                !EntityFolder.DRAFTS.equals(folder.type) &&
                !GmailLabelFolder.hasLabel(message.labels, DRAFT) &&
                GmailLabelFolder.hasLabel(message.labels, "INBOX"));
    }

    static String getDeleteOperation(EntityAccount account, boolean permanent) {
        return (permanent && account != null && account.isGmailApi()
                ? EntityOperation.GMAIL_PERMANENT_DELETE : EntityOperation.DELETE);
    }

    static boolean shouldSkipSingleDeleteConfirmation(boolean permanent, boolean deleteAsked) {
        return (deleteAsked && !permanent);
    }

    static boolean shouldRouteDeleteToTrash(EntityAccount account, EntityFolder source,
                                            EntityMessage message) {
        return (account != null && account.isGmailApi() && source != null &&
                source.account != null && source.account == account.id &&
                message != null && message.account == account.id &&
                message.gmail_id != null && message.gmail_id.length() > 0 &&
                !EntityFolder.TRASH.equals(source.type) &&
                !GmailLabelFolder.hasLabel(message.labels, TRASH));
    }

    static boolean canExpungeFolder(boolean gmailApiAccount, boolean selectable,
                                    String folderType, String gmailLabelId) {
        return (gmailApiAccount && selectable && EntityFolder.TRASH.equals(folderType) &&
                TRASH.equals(gmailLabelId));
    }

    static String mapFolderOperation(boolean gmailApiAccount, boolean selectable,
                                     String folderType, String gmailLabelId, String name) {
        if (!gmailApiAccount || (!EntityOperation.PURGE.equals(name) &&
                !EntityOperation.EXPUNGE.equals(name)))
            return name;
        if (!canExpungeFolder(true, selectable, folderType, gmailLabelId))
            return null;
        return EntityOperation.EXPUNGE;
    }

    static boolean isValidTarget(EntityAccount account, EntityFolder target) {
        if (account == null || !account.isGmailApi() || target == null || target.account == null ||
                account.id != target.account || !target.selectable || target.gmail_label_id == null ||
                target.gmail_label_id.length() == 0 || SENT.equals(target.gmail_label_id) ||
                DRAFT.equals(target.gmail_label_id))
            return false;
        return (EntityFolder.INBOX.equals(target.type) ||
                EntityFolder.ARCHIVE.equals(target.type) ||
                EntityFolder.TRASH.equals(target.type) ||
                (EntityFolder.JUNK.equals(target.type) && SPAM.equals(target.gmail_label_id)) ||
                EntityFolder.USER.equals(target.type));
    }

    static boolean isValidMovePickerTarget(EntityAccount account, EntityFolder target) {
        return (account != null && account.isGmailApi() && target != null &&
                target.account != null && account.id == target.account && target.selectable &&
                isValidGmailMovePickerTarget(target.type, target.gmail_label_id));
    }

    static boolean hasValidMovePickerTarget(EntityAccount account, List<EntityFolder> folders) {
        if (folders == null)
            return false;
        for (EntityFolder folder : folders)
            if (isValidMovePickerTarget(account, folder))
                return true;
        return false;
    }

    static boolean isValidGmailMovePickerTarget(String type, String labelId) {
        if (labelId == null || labelId.length() == 0 || SENT.equals(labelId) || DRAFT.equals(labelId) ||
                GmailLabelFolder.ALL_MAIL_VIEW_ID.equals(labelId))
            return false;
        if (EntityFolder.INBOX.equals(type))
            return "INBOX".equals(labelId);
        if (EntityFolder.TRASH.equals(type))
            return "TRASH".equals(labelId);
        if (EntityFolder.JUNK.equals(type))
            return "SPAM".equals(labelId);
        return (EntityFolder.USER.equals(type) &&
                !isManuallyApplicableSystemLabelId(labelId) &&
                !"UNREAD".equals(labelId) && !"STARRED".equals(labelId) &&
                !"IMPORTANT".equals(labelId));
    }

    static MoveCallbackValidation validateMoveCallback(EntityAccount account, EntityFolder source,
                                                       EntityMessage message, EntityFolder target) {
        return validateMoveCallback(account, source, message, target, source);
    }

    static MoveCallbackValidation validateMoveCallback(EntityAccount account, EntityFolder source,
                                                       EntityMessage message, EntityFolder target,
                                                       EntityFolder sourceView) {
        if (account == null || !account.isGmailApi())
            return MoveCallbackValidation.INVALID_TARGET;
        if (message == null || source == null || !isWritableMessage(account, source, message))
            return MoveCallbackValidation.SOURCE_MISSING;
        boolean aggregateSourceView = (sourceView != null && sourceView.account == null &&
                Boolean.TRUE.equals(sourceView.unified));
        if (sourceView != null && !Objects.equals(sourceView.account, account.id) &&
                !aggregateSourceView)
            return MoveCallbackValidation.INVALID_TARGET;
        EntityFolder moveSource = (sourceView == null || aggregateSourceView ? source : sourceView);
        String sourceLabel = resolveMoveSourceViewLabel(account, source, sourceView);
        if (sourceView == null)
            sourceLabel = (source == null ? null : source.gmail_label_id);
        if (!isValidMovePickerTarget(account, target) ||
                !canMoveToTarget(account, moveSource, message, target, sourceLabel))
            return MoveCallbackValidation.INVALID_TARGET;
        return MoveCallbackValidation.READY;
    }

    static List<String> getManuallyApplicableSystemLabelIds() {
        return Collections.unmodifiableList(Arrays.asList(
                "INBOX", "TRASH", "SPAM", "CATEGORY_PERSONAL", "CATEGORY_SOCIAL",
                "CATEGORY_PROMOTIONS", "CATEGORY_UPDATES", "CATEGORY_FORUMS"));
    }

    static boolean isManuallyApplicableSystemLabelId(String labelId) {
        return (labelId != null && getManuallyApplicableSystemLabelIds().contains(labelId));
    }

    static boolean isManuallyApplicableLabel(DB db, EntityAccount account, String labelId) {
        if (db == null || account == null || !account.isGmailApi() ||
                labelId == null || labelId.length() == 0 ||
                GmailLabelFolder.ALL_MAIL_VIEW_ID.equals(labelId) ||
                SENT.equals(labelId) || DRAFT.equals(labelId))
            return false;
        if (isManuallyApplicableSystemLabelId(labelId))
            return true;
        EntityFolder folder = db.folder().getFolderByGmailLabelId(account.id, labelId);
        return isWritableUserLabelTarget(folder, account.id);
    }

    static boolean isWritableUserLabelTarget(EntityFolder folder, long accountId) {
        return (folder != null && EntityFolder.USER.equals(folder.type) &&
                folder.account != null && folder.account == accountId && folder.selectable &&
                folder.gmail_label_id != null && !folder.gmail_label_id.isEmpty());
    }

    static boolean isValidMessageLabelTarget(EntityMessage message) {
        return (message != null && !GmailLabelFolder.hasLabel(message.labels, DRAFT));
    }

    static String resolveKeywordLabelId(DB db, EntityAccount account, String keyword) {
        if (db == null || account == null || !account.isGmailApi() || TextUtils.isEmpty(keyword))
            return null;
        if (MessageHelper.FLAG_HIGH_IMPORTANCE.equals(keyword) ||
                MessageHelper.FLAG_LOW_IMPORTANCE.equals(keyword))
            return IMPORTANT;
        if (keyword.matches("[A-Z0-9_]+")) {
            EntityFolder byId = db.folder().getFolderByGmailLabelId(account.id, keyword);
            if (byId != null && EntityFolder.USER.equals(byId.type))
                return byId.gmail_label_id;
        }
        List<EntityFolder> folders = db.folder().getFolders(account.id, true, true);
        if (folders != null)
            for (EntityFolder folder : folders)
                if (EntityFolder.USER.equals(folder.type) &&
                        !TextUtils.isEmpty(folder.gmail_label_id) &&
                        (keyword.equals(folder.display) || keyword.equals(folder.name)))
                    return folder.gmail_label_id;
        return null;
    }

    static List<String> applyLabelDelta(List<String> current, List<String> add, List<String> remove) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (current != null)
            result.addAll(current);
        if (remove != null)
            result.removeAll(remove);
        if (add != null)
            result.addAll(add);
        return new ArrayList<>(result);
    }

    static List<String> rollbackLabelDelta(List<String> optimistic, List<String> add,
                                           List<String> remove) {
        return applyLabelDelta(optimistic, remove, add);
    }

    static LocalState localStateFromLabels(List<String> labels) {
        List<String> safe = (labels == null ? Collections.<String>emptyList() : labels);
        return new LocalState(safe, !safe.contains(UNREAD), safe.contains(STARRED), safe.contains(TRASH));
    }

    static boolean hasLabelDelta(List<String> labels, List<String> add, List<String> remove) {
        if (labels == null)
            labels = Collections.emptyList();
        for (String label : add)
            if (!labels.contains(label))
                return false;
        for (String label : remove)
            if (labels.contains(label))
                return false;
        return true;
    }

    static MovePlan createMovePlan(String targetType, String targetLabel,
                                   String sourceType, String sourceLabel,
                                   boolean sourceIsUserLabel, List<String> currentLabels) {
        if ((SENT.equals(sourceLabel) || (sourceLabel == null && EntityFolder.SENT.equals(sourceType))) &&
                !EntityFolder.TRASH.equals(targetType))
            throw new IllegalArgumentException("Gmail manages the SENT label; use label management instead of Move");
        MovePlan result = new MovePlan();
        if (EntityFolder.TRASH.equals(targetType)) {
            result.trash = true;
            result.clearOtherLabels = true;
            result.add = Collections.singletonList(TRASH);
            return result;
        }
        boolean fromTrash = (EntityFolder.TRASH.equals(sourceType) ||
                (currentLabels != null && currentLabels.contains(TRASH)));
        result.untrash = fromTrash;
        if (EntityFolder.ARCHIVE.equals(targetType)) {
            // Archive is not an exclusive Gmail folder: remove only INBOX.
            result.remove = Collections.singletonList("INBOX");
        } else {
            result.add = Collections.singletonList(targetLabel);
            boolean removeSource = ("INBOX".equals(sourceLabel) || "SPAM".equals(sourceLabel) ||
                    sourceIsUserLabel);
            if (!fromTrash && removeSource && sourceLabel != null && sourceLabel.length() > 0 &&
                    !sourceLabel.equals(targetLabel))
                result.remove = Collections.singletonList(sourceLabel);
        }
        return result;
    }

    static String resolveMoveSourceLabel(String requestedLabel, String fallbackLabel) {
        if (requestedLabel == null || requestedLabel.length() == 0 || "null".equals(requestedLabel) ||
                GmailLabelFolder.ALL_MAIL_VIEW_ID.equals(requestedLabel))
            return fallbackLabel;
        return requestedLabel;
    }

    static String resolveMoveSourceLabelFromOperationArgs(JSONArray args, String fallbackLabel) {
        return resolveMoveSourceLabel(args == null ? null : args.optString(5, null), fallbackLabel);
    }

    static void process(Context context, DB db, EntityAccount account, GmailApi api,
                        Core.State state)
            throws IOException, JSONException, OperationCanceledException {
        if (context == null || db == null || account == null || !account.isGmailApi() ||
                api == null || state == null)
            throw new IllegalArgumentException("Missing Gmail mutation context");
        for (String name : Arrays.asList(EntityOperation.SEEN, EntityOperation.FLAG,
                EntityOperation.LABEL, EntityOperation.KEYWORD, EntityOperation.MOVE,
                EntityOperation.DELETE, EntityOperation.GMAIL_PERMANENT_DELETE))
            for (EntityOperation operation : db.operation().getOperations(account.id, name)) {
                if (operation.error != null)
                    continue;
                state.ensureRunning("Gmail mutation");
                String attemptToken = null;
                try {
                    attemptToken = GmailMutationLog.attemptToken(new JSONArray(operation.args));
                } catch (JSONException ignored) {
                    // Diagnostic token extraction must not change existing operation handling.
                }
                String previousAttempt = GmailMutationLog.setCurrentAttemptToken(attemptToken);
                try {
                    processMessage(context, db, account, api, state, operation);
                } finally {
                    GmailMutationLog.setCurrentAttemptToken(previousAttempt);
                }
            }
        for (EntityOperation operation : db.operation().getOperations(account.id, EntityOperation.EXPUNGE)) {
            if (operation.error != null)
                continue;
            state.ensureRunning("Gmail Trash expunge");
            processExpunge(db, account, api, state, operation);
        }
    }

    private static void processMessage(Context context, DB db, EntityAccount account, GmailApi api,
                                       Core.State state, EntityOperation operation)
            throws IOException, JSONException, OperationCanceledException {
        GmailMutationLog.event("worker_start", operation.name, "started");
        EntityMessage message = (operation.message == null ? null : db.message().getMessage(operation.message));
        EntityFolder source = (operation.folder == null ? null : db.folder().getFolder(operation.folder));
        if (!isWritableMessage(account, source, message)) {
            if (message != null)
                clearPendingUi(db, operation, message);
            failOperation(db, operation, "Gmail REST mutation has no valid Gmail message or folder");
            return;
        }
        if ((EntityOperation.DELETE.equals(operation.name) ||
                EntityOperation.GMAIL_PERMANENT_DELETE.equals(operation.name)) &&
                !canDeleteFromGmail(account, source, message)) {
            clearPendingUi(db, operation, message);
            failOperation(db, operation, "Gmail draft messages cannot use this Trash/delete operation");
            return;
        }
        if (EntityOperation.GMAIL_PERMANENT_DELETE.equals(operation.name)) {
            final EntityMessage permanentMessage = message;
            db.operation().setOperationState(operation.id, EXECUTING);
            try {
                permanentlyDeleteAndComplete(api, permanentMessage.gmail_id,
                        () -> completePermanentDelete(db, permanentMessage, operation));
            } catch (PermanentDeleteOutcomeUnknownException ex) {
                if (ex.labels != null)
                    reconcile(db, account, message, source, ex.labels);
                clearPendingUi(db, operation, message);
                failOperation(db, operation, ex.getMessage());
                return;
            } catch (OperationCanceledException ex) {
                clearPendingUi(db, operation, message);
                throw ex;
            } catch (Throwable ex) {
                if (reconcileAfterFailure(db, account, api, operation, message, source, null))
                    return;
                failOperation(db, operation, "Gmail permanent delete failed: " + Log.formatThrowable(ex));
                return;
            }
            return;
        }

        // Cached labels/folder placement can be stale after another Gmail client acts.
        // Read server labelIds before planning or replaying any local label mutation.
        final List<String> currentLabels;
        try {
            currentLabels = api.getMessageLabels(message.gmail_id);
            GmailMutationLog.event("readback_reconcile", operation.name, "success");
        } catch (GmailApi.MessageUnavailableException ex) {
            completeMissingMessage(db, message, operation);
            return;
        } catch (OperationCanceledException ex) {
            clearPendingUi(db, operation, message);
            throw ex;
        } catch (Throwable ex) {
            clearPendingUi(db, operation, message);
            failOperation(db, operation, "Gmail current labels could not be read; mutation was not attempted: " +
                    Log.formatThrowable(ex));
            return;
        }
        reconcile(db, account, message, source, currentLabels);
        message = db.message().getMessage(message.id);
        if (message == null) {
            db.operation().deleteOperation(operation.id);
            return;
        }

        final Plan plan;
        try {
            plan = createPlan(db, account, operation, message, source);
        } catch (IllegalArgumentException ex) {
            reconcileAfterFailure(db, account, api, operation, message, source, null);
            failOperation(db, operation, ex.getMessage());
            return;
        } catch (JSONException ex) {
            reconcileAfterFailure(db, account, api, operation, message, source, null);
            failOperation(db, operation, "Invalid Gmail REST operation arguments: " + Log.formatThrowable(ex));
            return;
        }

        final EntityMessage reconciledMessage = message;
        final EntityFolder reconciledSource = source;
        final OperationLifecycle lifecycle = new OperationLifecycle() {
            @Override
            public void setState(String value) {
                db.operation().setOperationState(operation.id, value);
            }

            @Override
            public void complete(List<String> labels) {
                reconcile(db, account, reconciledMessage, reconciledSource, labels);
                db.operation().deleteOperation(operation.id);
                GmailMutationLog.event("local_commit", operation.name, "confirmed");
                GmailMutationLog.event("operation_summary", operation.name, "success");
            }
        };

        try {
            applyAndCommit(api, plan, message.gmail_id, currentLabels, lifecycle);
        } catch (OperationCanceledException ex) {
            clearPendingUi(db, operation, message);
            throw ex;
        } catch (Throwable ex) {
            // A timed-out write can still have reached Gmail. Read back before rollback/retry state is recorded.
            if (reconcileAfterFailure(db, account, api, operation, message, source, plan))
                return;
            failOperation(db, operation, "Gmail REST mutation failed: " + Log.formatThrowable(ex));
        }
    }

    private static boolean reconcileAfterFailure(DB db, EntityAccount account, GmailApi api,
                                                 EntityOperation operation, EntityMessage message,
                                                 EntityFolder source, Plan plan)
            throws OperationCanceledException {
        RecoveryResult result = reconcileFailedWrite(api, message.gmail_id, plan,
                new FailureLifecycle() {
                    @Override
                    public void reconcile(List<String> labels) {
                        GmailMailMutator.reconcile(db, account, message, source, labels);
                    }

                    @Override
                    public void clearPending() {
                        GmailMailMutator.clearPendingUi(db, operation, message);
                    }

                    @Override
                    public void missing() {
                        completeMissingMessage(db, message, operation);
                    }

                    @Override
                    public void readbackFailed(Throwable ex) {
                        Log.w("Gmail mutation reconciliation failed message=" + message.id, ex);
                    }
                });
        String outcome = (result == RecoveryResult.SATISFIED ? "satisfied" :
                result == RecoveryResult.NOT_SATISFIED ? "not_satisfied" :
                        result == RecoveryResult.MISSING ? "missing" : "unavailable");
        GmailMutationLog.event("readback_reconcile", operation.name, outcome);
        if (result == RecoveryResult.SATISFIED) {
            db.operation().deleteOperation(operation.id);
            GmailMutationLog.event("local_commit", operation.name, "confirmed");
            GmailMutationLog.event("operation_summary", operation.name, "success");
            return true;
        }
        return (result == RecoveryResult.MISSING);
    }

    static RecoveryResult reconcileFailedWrite(GmailApi api, String gmailId, Plan plan,
                                                FailureLifecycle lifecycle)
            throws OperationCanceledException {
        try {
            List<String> current = api.getMessageLabels(gmailId);
            lifecycle.reconcile(current);
            if (plan != null && plan.isSatisfied(current))
                return RecoveryResult.SATISFIED;
            lifecycle.clearPending();
            return RecoveryResult.NOT_SATISFIED;
        } catch (GmailApi.MessageUnavailableException ex) {
            lifecycle.missing();
            return RecoveryResult.MISSING;
        } catch (OperationCanceledException ex) {
            lifecycle.clearPending();
            throw ex;
        } catch (Throwable readError) {
            lifecycle.readbackFailed(readError);
            lifecycle.clearPending();
            return RecoveryResult.UNAVAILABLE;
        }
    }

    enum RecoveryResult {
        SATISFIED,
        NOT_SATISFIED,
        MISSING,
        UNAVAILABLE
    }

    interface FailureLifecycle {
        void reconcile(List<String> labels);

        void clearPending();

        void missing();

        void readbackFailed(Throwable ex);
    }

    private static void clearPendingUi(DB db, EntityOperation operation, EntityMessage message) {
        db.message().setMessageUiHide(message.id, false);
        db.message().setMessageUiBusy(message.id, null);
        db.message().setMessageUiDeleted(message.id, false);
    }

    private static void completeMissingMessage(DB db, EntityMessage message, EntityOperation operation) {
        db.beginTransaction();
        try {
            if (message != null)
                db.message().deleteMessage(message.id);
            db.operation().deleteOperation(operation.id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        GmailMutationLog.event("local_commit", operation.name, "confirmed");
        GmailMutationLog.event("operation_summary", operation.name, "success");
    }

    private static void completePermanentDelete(DB db, EntityMessage message, EntityOperation operation) {
        db.beginTransaction();
        try {
            db.message().deleteMessage(message.id);
            db.operation().deleteOperation(operation.id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        GmailMutationLog.event("local_commit", operation.name, "confirmed");
        GmailMutationLog.event("operation_summary", operation.name, "success");
    }

    private static Plan createPlan(DB db, EntityAccount account, EntityOperation operation,
                                   EntityMessage message, EntityFolder source)
            throws JSONException {
        JSONArray args = new JSONArray(operation.args);
        Plan plan = new Plan();
        if (EntityOperation.SEEN.equals(operation.name)) {
            return createSeenPlan(args.getBoolean(0));
        } else if (EntityOperation.FLAG.equals(operation.name)) {
            boolean flagged = args.getBoolean(0);
            plan.add = (flagged ? Collections.singletonList(STARRED) : Collections.<String>emptyList());
            plan.remove = (flagged ? Collections.<String>emptyList() : Collections.singletonList(STARRED));
        } else if (EntityOperation.LABEL.equals(operation.name) ||
                EntityOperation.KEYWORD.equals(operation.name)) {
            String value = args.getString(0);
            boolean set = args.getBoolean(1);
            String labelId;
            if (EntityOperation.KEYWORD.equals(operation.name)) {
                labelId = resolveKeywordLabelId(db, account, value);
                if (MessageHelper.FLAG_LOW_IMPORTANCE.equals(value))
                    set = !set;
            } else {
                labelId = value;
                if (!isManuallyApplicableLabel(db, account, labelId) ||
                        EntityFolder.DRAFTS.equals(source.type) ||
                        GmailLabelFolder.hasLabel(message.labels, DRAFT))
                    labelId = null;
            }
            if (TextUtils.isEmpty(labelId))
                throw new IllegalArgumentException("Gmail label/keyword is not a writable Gmail label");
            return createLabelPlan(labelId, set);
        } else if (EntityOperation.DELETE.equals(operation.name)) {
            return createTrashPlan();
        } else if (EntityOperation.MOVE.equals(operation.name)) {
            long targetId = args.getLong(0);
            EntityFolder target = db.folder().getFolder(targetId);
            String sourceLabel = resolveMoveSourceLabelFromOperationArgs(args, source.gmail_label_id);
            if (!isValidTarget(account, target))
                throw new IllegalArgumentException("Gmail move target is not a writable Gmail label");
            if (!canMoveToTarget(account, source, message, target, sourceLabel))
                throw new IllegalArgumentException("Gmail move source or destination is not writable");
            plan.targetFolder = target;
            plan.changesFolder = true;
            EntityFolder sourceLabelFolder = (sourceLabel == null ? null :
                    db.folder().getFolderByGmailLabelId(account.id, sourceLabel));
            MovePlan move = createMovePlan(target.type, target.gmail_label_id,
                    source.type, sourceLabel,
                    (sourceLabelFolder != null && EntityFolder.USER.equals(sourceLabelFolder.type)) ||
                            (EntityFolder.USER.equals(source.type) && sourceLabel != null),
                    message.labels == null ? Collections.<String>emptyList() : Arrays.asList(message.labels));
            plan.trash = move.trash;
            plan.untrash = move.untrash;
            plan.clearOtherLabels = move.clearOtherLabels;
            plan.add = move.add;
            plan.remove = move.remove;
        } else
            throw new IllegalArgumentException("Unsupported Gmail REST operation " + operation.name);
        return plan;
    }

    static Plan createSeenPlan(boolean seen) {
        Plan plan = new Plan();
        plan.add = (seen ? Collections.<String>emptyList() : Collections.singletonList(UNREAD));
        plan.remove = (seen ? Collections.singletonList(UNREAD) : Collections.<String>emptyList());
        return plan;
    }

    static Plan createLabelPlan(String labelId, boolean set) {
        if (labelId == null || labelId.isEmpty() || SENT.equals(labelId) || DRAFT.equals(labelId) ||
                GmailLabelFolder.ALL_MAIL_VIEW_ID.equals(labelId))
            throw new IllegalArgumentException("Gmail system label cannot be manually changed");
        Plan plan = new Plan();
        plan.add = (set ? Collections.singletonList(labelId) : Collections.<String>emptyList());
        plan.remove = (set ? Collections.<String>emptyList() : Collections.singletonList(labelId));
        return plan;
    }

    static Plan createTrashPlan() {
        Plan plan = new Plan();
        plan.trash = true;
        plan.clearOtherLabels = true;
        plan.changesFolder = true;
        plan.add = Collections.singletonList(TRASH);
        return plan;
    }

    static List<String> applyAndVerify(GmailApi api, Plan plan, String gmailId,
                                       OperationLifecycle lifecycle)
            throws IOException, JSONException {
        lifecycle.setState(EXECUTING);
        List<String> labels = apply(api, plan, gmailId);
        if (!plan.isSatisfied(labels))
            throw new IOException("Gmail mutation response did not contain the requested label state");
        return labels;
    }

    static boolean completeIfSatisfied(Plan plan, List<String> labels,
                                       OperationLifecycle lifecycle) {
        if (!plan.isSatisfied(labels))
            return false;
        lifecycle.complete(labels);
        return true;
    }

    static List<String> applyAndCommit(GmailApi api, Plan plan, String gmailId,
                                       List<String> currentLabels, OperationLifecycle lifecycle)
            throws IOException, JSONException {
        if (completeIfSatisfied(plan, currentLabels, lifecycle))
            return new ArrayList<>(currentLabels);
        List<String> updated = applyAndVerify(api, plan, gmailId, lifecycle);
        // A successful REST response, with its returned labelIds, is the local commit point.
        lifecycle.complete(updated);
        return updated;
    }

    private static List<String> apply(GmailApi api, Plan plan, String gmailId)
            throws IOException, JSONException {
        List<String> labels;
        if (plan.trash) {
            labels = api.trashMessage(gmailId);
            if (plan.clearOtherLabels)
                labels = removeOtherLabels(api, gmailId, labels);
        }
        else if (plan.untrash) {
            labels = api.untrashMessage(gmailId);
            if (!plan.add.isEmpty() || !plan.remove.isEmpty())
                labels = api.modifyMessage(gmailId, plan.add, plan.remove);
        } else if (!plan.add.isEmpty() || !plan.remove.isEmpty())
            labels = api.modifyMessage(gmailId, plan.add, plan.remove);
        else
            labels = api.getMessageLabels(gmailId);
        return labels;
    }

    static List<String> labelsToRemoveFromTrash(List<String> labels) {
        List<String> remove = new ArrayList<>();
        if (labels != null)
            for (String label : labels)
                if (label != null && !isTrashProtectedSystemLabel(label) && !remove.contains(label))
                    remove.add(label);
        return remove;
    }

    private static boolean isTrashProtectedSystemLabel(String label) {
        // Gmail applies SENT/DRAFT automatically; these are system state, not labels
        // a client can reliably remove with messages.modify.
        return (TRASH.equals(label) || "SENT".equals(label) || "DRAFT".equals(label));
    }

    private static List<String> removeOtherLabels(GmailApi api, String gmailId, List<String> labels)
            throws IOException, JSONException {
        for (int pass = 0; pass < MAX_TRASH_CLEANUP_PASSES; pass++) {
            List<String> remove = labelsToRemoveFromTrash(labels);
            if (remove.isEmpty())
                return labels;
            int before = remove.size();
            for (int start = 0; start < remove.size(); start += MAX_LABELS_PER_MODIFY) {
                int end = Math.min(start + MAX_LABELS_PER_MODIFY, remove.size());
                labels = api.modifyMessage(gmailId, Collections.<String>emptyList(),
                        new ArrayList<>(remove.subList(start, end)));
            }
            if (labelsToRemoveFromTrash(labels).size() >= before)
                throw new IOException("Gmail Trash label cleanup made no progress");
        }
        throw new IOException("Gmail Trash label cleanup exceeded its bounded retry limit");
    }

    static boolean isTrashReadyForPermanentDelete(List<String> labels) {
        return (labels != null && labels.contains(TRASH) && !labels.contains(DRAFT) &&
                labelsToRemoveFromTrash(labels).isEmpty());
    }

    static void permanentlyDelete(GmailApi api, String gmailId) throws IOException, JSONException {
        List<String> labels;
        try {
            labels = api.getMessageLabels(gmailId);
        } catch (GmailApi.MessageUnavailableException alreadyGone) {
            return;
        }
        if (labels != null && labels.contains(DRAFT))
            throw new IOException("Gmail draft messages cannot be trashed with messages.modify");

        if (!isTrashReadyForPermanentDelete(labels)) {
            try {
                labels = preparePermanentDeleteTrash(api, gmailId, labels);
            } catch (GmailApi.MessageUnavailableException alreadyGone) {
                return;
            } catch (IOException | JSONException modifyFailure) {
                try {
                    labels = api.getMessageLabels(gmailId);
                } catch (GmailApi.MessageUnavailableException alreadyGone) {
                    return;
                } catch (IOException | JSONException reconcileFailure) {
                    modifyFailure.addSuppressed(reconcileFailure);
                    throw new IOException("Gmail Trash preparation could not be reconciled; permanent delete was not attempted",
                            modifyFailure);
                }
                if (!isTrashReadyForPermanentDelete(labels))
                    throw new IOException("Gmail Trash preparation was not confirmed; permanent delete was not attempted",
                            modifyFailure);
            }
        }

        if (!isTrashReadyForPermanentDelete(labels))
            throw new IOException("Gmail message is not confirmed in clean Trash; permanent delete was not attempted");
        try {
            api.deleteMessage(gmailId);
        } catch (GmailApi.MessageUnavailableException alreadyGone) {
            return;
        } catch (GmailApi.DeleteOutcomeUnknownException unknown) {
            try {
                labels = api.getMessageLabels(gmailId);
            } catch (GmailApi.MessageUnavailableException confirmedGone) {
                return;
            } catch (IOException | JSONException readFailure) {
                unknown.addSuppressed(readFailure);
                throw new PermanentDeleteOutcomeUnknownException(null, unknown);
            }
            throw new PermanentDeleteOutcomeUnknownException(labels, unknown);
        }
    }

    interface PermanentDeleteCompletion {
        void complete();
    }

    static void permanentlyDeleteAndComplete(GmailApi api, String gmailId,
                                               PermanentDeleteCompletion completion)
            throws IOException, JSONException {
        permanentlyDelete(api, gmailId);
        // The callback owns local DB/queue removal and runs only after success or confirmed 404 absence.
        completion.complete();
    }

    private static List<String> preparePermanentDeleteTrash(GmailApi api, String gmailId,
                                                              List<String> current)
            throws IOException, JSONException {
        if (current != null && current.contains(DRAFT))
            throw new IOException("Gmail draft messages cannot be trashed with messages.modify");
        List<String> remove = labelsToRemoveFromTrash(current);
        boolean addTrash = (current == null || !current.contains(TRASH));
        int calls = Math.max(1, (remove.size() + MAX_LABELS_PER_MODIFY - 1) / MAX_LABELS_PER_MODIFY);
        List<String> labels = current;
        for (int index = 0; index < calls; index++) {
            int start = index * MAX_LABELS_PER_MODIFY;
            int end = Math.min(start + MAX_LABELS_PER_MODIFY, remove.size());
            List<String> removeBatch = (start < end
                    ? new ArrayList<>(remove.subList(start, end)) : Collections.<String>emptyList());
            List<String> addBatch = (index == 0 && addTrash
                    ? Collections.singletonList(TRASH) : Collections.<String>emptyList());
            labels = api.modifyMessage(gmailId, addBatch, removeBatch);
            if ((addTrash && !labels.contains(TRASH)) ||
                    !Collections.disjoint(labels, removeBatch))
                throw new IOException("Gmail messages.modify response did not confirm the requested Trash delta");
        }
        if (!isTrashReadyForPermanentDelete(labels))
            throw new IOException("Gmail messages.modify did not confirm clean Trash membership");
        return labels;
    }

    static final class PermanentDeleteOutcomeUnknownException extends IOException {
        final List<String> labels;

        PermanentDeleteOutcomeUnknownException(List<String> labels, IOException cause) {
            super("Gmail permanent delete outcome is unknown; the queued operation was quarantined without replay",
                    cause);
            this.labels = (labels == null ? null : new ArrayList<>(labels));
        }
    }

    private static void processExpunge(DB db, EntityAccount account, GmailApi api,
                                       Core.State state, EntityOperation operation)
            throws IOException, JSONException, OperationCanceledException {
        GmailMutationLog.event("durable_queue", operation.name, "stored");
        GmailMutationLog.event("worker_start", operation.name, "started");
        EntityFolder folder = (operation.folder == null ? null : db.folder().getFolder(operation.folder));
        if (!canExpungeFolder(true, folder != null && folder.selectable,
                folder == null ? null : folder.type,
                folder == null ? null : folder.gmail_label_id)) {
            failOperation(db, operation, "Gmail permanent deletion is restricted to the Gmail Trash label");
            return;
        }
        db.operation().setOperationState(operation.id, EXECUTING);
        List<EntityMessage> messages = db.message().getGmailMessagesByLabel(account.id, TRASH);
        for (EntityMessage message : messages) {
            if (!GmailLabelFolder.hasLabel(message.labels, TRASH))
                continue;
            if (message.gmail_id == null || message.gmail_id.trim().isEmpty()) {
                failOperation(db, operation,
                        "Gmail Trash contains a local row without a Gmail message ID");
                return;
            }
            state.ensureRunning("Gmail Trash expunge");
            try {
                permanentlyDelete(api, message.gmail_id);
            } catch (IOException | JSONException ex) {
                failOperation(db, operation, "Gmail Trash expunge failed: " + Log.formatThrowable(ex));
                return;
            }
            db.beginTransaction();
            try {
                db.message().deleteMessage(message.id);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        db.operation().deleteOperation(operation.id);
        GmailMutationLog.event("local_commit", operation.name, "confirmed");
        GmailMutationLog.event("operation_summary", operation.name, "success");
    }

    private static void reconcile(DB db, EntityAccount account, EntityMessage message,
                                  EntityFolder source, List<String> labels) {
        GmailMessageLabelState local = GmailMessageLabelState.from(labels);
        List<String> safeLabels = local.labels;
        Long storage = getStorageFolder(db, account, safeLabels, source);
        db.beginTransaction();
        try {
            db.message().setMessageLabels(message.id, DB.Converters.fromStringArray(
                    safeLabels.toArray(new String[0])));
            db.message().setMessageSeen(message.id, local.seen);
            db.message().setMessageUiSeen(message.id, local.seen);
            db.message().setMessageFlagged(message.id, local.flagged);
            db.message().setMessageUiFlagged(message.id, local.flagged, local.flagged ? message.color : null);
            db.message().setMessageImportance(message.id, local.importance);
            db.message().setMessageDeleted(message.id, local.deleted);
            db.message().setMessageUiDeleted(message.id, false);
            db.message().setMessageAnswered(message.id, local.answered);
            db.message().setMessageUiAnswered(message.id, local.answered);
            if (storage != null)
                db.message().setMessageFolder(message.id, storage);
            db.message().setMessageUiHide(message.id, false);
            db.message().setMessageUiBusy(message.id, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        reconcileFailedOperations(db, message.id, safeLabels);
    }

    static int reconcileFailedOperations(DB db, long messageId, List<String> labels) {
        EntityMessage message = db.message().getMessage(messageId);
        if (message == null || TextUtils.isEmpty(message.gmail_id))
            return 0;
        EntityAccount account = db.account().getAccount(message.account);
        if (account == null || !account.isGmailApi())
            return 0;

        int resolved = 0;
        boolean unresolvedError = false;
        for (EntityOperation operation : db.operation().getOperationsForMessage(messageId)) {
            if (TextUtils.isEmpty(operation.error))
                continue;
            if (!EntityOperation.isGmailApiMutation(operation.name)) {
                unresolvedError = true;
                continue;
            }

            EntityFolder source = (operation.folder == null ? null : db.folder().getFolder(operation.folder));
            if (source == null)
                source = db.folder().getFolder(message.folder);
            try {
                Plan plan = createPlan(db, account, operation, message, source);
                if (isFailedOperationSatisfied(operation, plan, labels))
                    resolved += db.operation().deleteOperation(operation.id);
                else
                    unresolvedError = true;
            } catch (JSONException | RuntimeException ex) {
                unresolvedError = true;
            }
        }

        if (resolved > 0 && !unresolvedError)
            db.message().setMessageError(messageId, null);
        return resolved;
    }

    static boolean isFailedOperationSatisfied(EntityOperation operation, Plan plan, List<String> labels) {
        return (operation != null && operation.error != null && !operation.error.isEmpty() && plan != null &&
                plan.isSatisfied(labels));
    }

    private static Long getStorageFolder(DB db, EntityAccount account,
                                         List<String> labels, EntityFolder fallback) {
        for (String id : Arrays.asList("TRASH", "INBOX", "DRAFT", "SENT", "SPAM"))
            if (labels.contains(id)) {
                EntityFolder folder = db.folder().getFolderByGmailLabelId(account.id, id);
                if (folder != null)
                    return folder.id;
            }
        for (String id : labels) {
            EntityFolder folder = db.folder().getFolderByGmailLabelId(account.id, id);
            if (folder != null && EntityFolder.USER.equals(folder.type))
                return folder.id;
        }
        EntityFolder allMail = db.folder().getFolderByGmailLabelId(account.id,
                EntityFolder.GMAIL_API_ALL_MAIL_VIEW_ID);
        return (allMail == null ? (fallback == null ? null : fallback.id) : allMail.id);
    }

    private static void failOperation(DB db, EntityOperation operation, String error) {
        String detail = (TextUtils.isEmpty(error) ? "Gmail REST operation failed" : error);
        db.operation().setOperationState(operation.id, null);
        db.operation().setOperationError(operation.id, detail);
        if (operation.message != null)
            db.message().setMessageError(operation.message, detail);
        GmailMutationLog.event("failure_summary", operation.name, "failure");
    }

    interface OperationLifecycle {
        void setState(String state);

        void complete(List<String> labels);
    }

    static final class Plan {
        List<String> add = Collections.emptyList();
        List<String> remove = Collections.emptyList();
        boolean trash;
        boolean clearOtherLabels;
        boolean untrash;
        boolean changesFolder;
        EntityFolder targetFolder;

        boolean isSatisfied(List<String> labels) {
            if (trash)
                return labels != null && labels.contains(TRASH) &&
                        (!clearOtherLabels || labelsToRemoveFromTrash(labels).isEmpty());
            if (untrash && labels != null && labels.contains(TRASH))
                return false;
            return hasLabelDelta(labels, add, remove);
        }
    }

    static final class MovePlan {
        List<String> add = Collections.emptyList();
        List<String> remove = Collections.emptyList();
        boolean trash;
        boolean clearOtherLabels;
        boolean untrash;
    }

    static final class LocalState {
        final List<String> labels;
        final boolean seen;
        final boolean flagged;
        final boolean trashed;

        LocalState(List<String> labels, boolean seen, boolean flagged, boolean trashed) {
            this.labels = Collections.unmodifiableList(new ArrayList<>(labels));
            this.seen = seen;
            this.flagged = flagged;
            this.trashed = trashed;
        }
    }
}
