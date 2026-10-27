package eu.faircode.email;

/*
    This file is part of FairEmail.
    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.
    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

final class MessageSelectionCapabilities {
    private MessageSelectionCapabilities() {
    }

    static boolean canMoveToFolder(boolean readOnly,
                                   boolean graphApiSelected,
                                   boolean graphApiReadOnly,
                                   boolean singleWritableGraphAccount,
                                   boolean hasPop,
                                   Boolean leaveOnServer,
                                   boolean hasLegacyMoveTargets) {
        if (readOnly || graphApiReadOnly)
            return false;

        if (graphApiSelected)
            return singleWritableGraphAccount;

        return (!hasPop || Boolean.TRUE.equals(leaveOnServer)) && hasLegacyMoveTargets;
    }

    static boolean canMoveToZohoFolder(boolean readOnly, boolean zohoApiReadOnly,
                                       boolean singleWritableZohoAccount) {
        return (!readOnly && !zohoApiReadOnly && singleWritableZohoAccount);
    }

    static boolean canQueueZohoReadFlag(boolean hasZohoId, boolean folderReadOnly) {
        return (hasZohoId && !folderReadOnly);
    }

    static boolean canShowHeaders(boolean hasUid, boolean nativeRestMessage,
                                  boolean pop, String headers) {
        return (hasUid || ((nativeRestMessage || pop) && headers != null && headers.length() > 0));
    }

    static boolean canReportGmailSpam(boolean gmailApiSelected, boolean writable,
                                      boolean singleAccount, boolean hasSpamTarget,
                                      boolean isSent, boolean isSpam, boolean isDraft) {
        return (gmailApiSelected && writable && singleAccount && hasSpamTarget &&
                !isSent && !isSpam && !isDraft);
    }
    static boolean canMoveFromGmailSource(boolean gmailApiMessage, boolean sentSource) {
        return (!gmailApiMessage || !sentSource);
    }
    static boolean canTrashGmailMessage(boolean gmailApiMessage, boolean writableNonDraft,
                                        boolean hasTrashTarget, boolean isTrash) {
        return (gmailApiMessage && writableNonDraft && hasTrashTarget && !isTrash);
    }

    static boolean canReturnGmailMessageToInbox(boolean gmailApiSelected, boolean writableNonDraft,
                                               boolean hasInboxTarget, boolean isSent, boolean isInbox) {
        return (gmailApiSelected && writableNonDraft && hasInboxTarget && !isSent && !isInbox);
    }

    static boolean canSetImportance(boolean nativeRestMessage, boolean gmailApiAccount,
                                    boolean hasGmailId, boolean graphApiAccount,
                                    boolean zohoApiAccount) {
        if (graphApiAccount || zohoApiAccount)
            return false;
        if (gmailApiAccount)
            return (nativeRestMessage && hasGmailId);
        return (!nativeRestMessage && !hasGmailId);
    }

    static boolean canDeletePermanently(boolean canDelete, boolean gmailApiSelected,
                                        boolean graphApiSelected, boolean zohoApiSelected,
                                        boolean zohoPermanentDelete, boolean allSelectedInTrashOrSpam) {
        if (!canDelete)
            return false;
        boolean nativeApiSelected = (gmailApiSelected || graphApiSelected || zohoApiSelected);
        if (nativeApiSelected && allSelectedInTrashOrSpam)
            return false;
        return (!zohoApiSelected || zohoPermanentDelete);
    }

    static boolean shouldLabelDeletePermanentlyMenu(boolean gmailApiMessage,
                                                    boolean graphApiMessage,
                                                    boolean zohoApiMessage,
                                                    String folderType) {
        return (gmailApiMessage || graphApiMessage || zohoApiMessage);
    }

    static boolean shouldShowPermanentDeleteMoreAction(boolean nativeRestMessage,
                                                       String folderType) {
        return (nativeRestMessage && !isTrashOrSpam(folderType));
    }

    static boolean isTrashOrSpam(String folderType) {
        return (EntityFolder.TRASH.equals(folderType) || EntityFolder.JUNK.equals(folderType));
    }

    static boolean isZohoPermanentIndividualDelete(boolean zohoApiMessage, String folderType) {
        return (zohoApiMessage &&
                (EntityFolder.TRASH.equals(folderType) || EntityFolder.JUNK.equals(folderType)));
    }

    static boolean shouldShowLabelChangeToast(boolean gmailApiMessage) {
        return !gmailApiMessage;
    }

    static boolean shouldShowReadStateDiffToast(boolean gmailApiMessage) {
        // Read/open-related diff logs are useful for diagnosis but noisy as toasts for Gmail API.
        return !gmailApiMessage;
    }

    static boolean shouldShowUiSeenChangeToast(boolean gmailApiMessage) {
        return shouldShowReadStateDiffToast(gmailApiMessage);
    }

    static boolean canModifySelection(boolean readOnly,
                                      boolean gmailApiReadOnly,
                                      boolean graphApiReadOnly,
                                      boolean zohoApiReadOnly) {
        return (!readOnly && !gmailApiReadOnly && !graphApiReadOnly && !zohoApiReadOnly);
    }

    static boolean shouldShowPermanentDeleteConfirmation(boolean deleteConfirmation,
                                                         boolean explicitApiPermanentDelete) {
        return (deleteConfirmation || explicitApiPermanentDelete);
    }

    static boolean shouldDeleteLocallyWithoutUid(boolean gmailApiAccount,
                                                 boolean graphApiAccount,
                                                 boolean zohoApiAccount,
                                                 int protocol, boolean hasUid) {
        return (!gmailApiAccount && !graphApiAccount && !zohoApiAccount && !hasUid &&
                protocol == EntityAccount.TYPE_IMAP);
    }

    static boolean canMoveGraphMessage(boolean graphApiSource,
                                       boolean copy,
                                       Long sourceAccount,
                                       boolean graphApiTarget,
                                       Long targetAccount,
                                       boolean targetSelectable,
                                       boolean targetReadOnly,
                                       String graphFolderId) {
        return (graphApiSource && !copy && sourceAccount != null &&
                sourceAccount.equals(targetAccount) && graphApiTarget && targetSelectable &&
                !targetReadOnly && graphFolderId != null && graphFolderId.length() > 0);
    }

    static boolean canQueueNativeReadFlag(boolean nativeRestMessage,
                                          boolean zohoApiAccount, boolean hasZohoId,
                                          boolean graphApiAccount, boolean hasGraphId) {
        return canQueueNativeReadFlag(nativeRestMessage, false, false,
                zohoApiAccount, hasZohoId, graphApiAccount, hasGraphId);
    }

    static boolean canQueueNativeReadFlag(boolean nativeRestMessage,
                                          boolean gmailApiAccount, boolean hasGmailId,
                                          boolean zohoApiAccount, boolean hasZohoId,
                                          boolean graphApiAccount, boolean hasGraphId) {
        if (!nativeRestMessage)
            return true;
        return ((gmailApiAccount && hasGmailId) || (zohoApiAccount && hasZohoId) ||
                (graphApiAccount && hasGraphId));
    }

    static boolean canMoveToGmailFolder(boolean gmailApiSelected, boolean gmailApiReadOnly,
                                        boolean oneGmailAccount, boolean hasWritableTarget) {
        return (gmailApiSelected && !gmailApiReadOnly && oneGmailAccount && hasWritableTarget);
    }
    static boolean canMoveToGmailFolder(boolean gmailApiSelected, boolean gmailApiReadOnly,
                                        boolean oneGmailAccount, boolean hasWritableTarget,
                                        boolean sentSource) {
        return (canMoveToGmailFolder(gmailApiSelected, gmailApiReadOnly,
                oneGmailAccount, hasWritableTarget) &&
                canMoveFromGmailSource(gmailApiSelected, sentSource));
    }

    static boolean shouldShowLegacyOnlyMessageMoreAction(boolean nativeRestMessage) {
        return !nativeRestMessage;
    }

    static boolean shouldShowNativeMessageMoreAction(boolean nativeRestMessage,
                                                     boolean supportedNativeAction) {
        return (!nativeRestMessage || supportedNativeAction);
    }

    static boolean shouldShowMessageMoreResync(boolean nativeRestMessage,
                                               boolean graphApiMessage,
                                               boolean legacyResyncGate) {
        return (graphApiMessage || (!nativeRestMessage && legacyResyncGate));
    }

    static boolean shouldShowMessageMoreRawSave(boolean nativeRestMessage,
                                                boolean hasRemoteRawIdentifier) {
        return (!nativeRestMessage || hasRemoteRawIdentifier);
    }

    static boolean canShowCopyTo(boolean graphApiMessage, boolean zohoApiMessage,
                                 boolean gmailApiMessage, int protocol) {
        return (protocol == EntityAccount.TYPE_IMAP && !graphApiMessage &&
                !zohoApiMessage && !gmailApiMessage);
    }
}
