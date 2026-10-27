package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/

final class FolderActionCapabilities {
    private FolderActionCapabilities() {
    }

    static boolean isNativeRestFolder(TupleFolderEx folder) {
        if (folder == null)
            return false;
        return (folder.accountAuthType != null &&
                (folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_GMAIL ||
                        folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_GRAPH_API ||
                        folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_ZOHO)) ||
                folder.gmail_label_id != null || folder.graph_folder_id != null ||
                folder.zoho_folder_id != null || ZohoOAuth.isProviderId(folder.accountProvider);
    }

    static boolean canManageLegacyImapFolders(TupleFolderEx folder) {
        return (folder != null && folder.account != null &&
                folder.accountProtocol != null && folder.accountProtocol == EntityAccount.TYPE_IMAP &&
                !isNativeRestFolder(folder));
    }

    static boolean canFetchMore(TupleFolderEx folder) {
        if (folder == null || !isNativeRestFolder(folder))
            return true;
        if ((folder.accountAuthType != null &&
                folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_GMAIL) ||
                folder.gmail_label_id != null)
            return GmailReceiver.canFetchMoreManually(folder.selectable,
                    folder.gmail_label_id, folder.gmail_page_token);
        if (!Boolean.TRUE.equals(folder.download))
            return false;
        if ((folder.accountAuthType != null &&
                folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_GRAPH_API) ||
                folder.graph_folder_id != null)
            return GraphMailReceiver.canFetchMoreManually(folder.selectable,
                    folder.graph_folder_id, folder.graph_delta_link, folder.graph_page_link);
        if ((folder.accountAuthType != null &&
                folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_ZOHO) ||
                folder.zoho_folder_id != null || ZohoOAuth.isProviderId(folder.accountProvider))
            return ZohoReceiver.canFetchMoreManually(folder.selectable,
                    folder.zoho_folder_id, folder.zoho_page_start);
        return true;
    }

    static boolean canExpunge(EntityAccount account, EntityFolder folder) {
        if (!isSameSelectableFolder(account, folder))
            return false;

        if (account.isGmailApi())
            return GmailMailMutator.canExpungeFolder(true, folder.selectable,
                    folder.type, folder.gmail_label_id);
        if (account.isZohoApi())
            return ZohoMailMutator.canEmptyTrash(account, folder);
        if (account.isGraphApi())
            return false;

        return (account.protocol == EntityAccount.TYPE_IMAP && !folder.read_only);
    }

    static boolean canExpunge(TupleFolderEx folder) {
        if (folder == null || folder.account == null || !folder.selectable)
            return false;

        if (folder.accountAuthType != null &&
                folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_GRAPH_API)
            return false;
        if (folder.accountAuthType != null &&
                folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_ZOHO)
            return (EntityFolder.TRASH.equals(folder.type) && !folder.read_only &&
                    folder.zoho_folder_id != null && folder.zoho_folder_id.length() > 0 &&
                    ZohoApi.isValidRemoteId(folder.zoho_folder_id));
        if (folder.accountAuthType != null &&
                folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_GMAIL)
            return GmailMailMutator.canExpungeFolder(true, folder.selectable,
                    folder.type, folder.gmail_label_id);
        if (folder.gmail_label_id != null)
            return GmailMailMutator.canExpungeFolder(true, folder.selectable,
                    folder.type, folder.gmail_label_id);
        if (folder.graph_folder_id != null)
            return false;
        if (folder.zoho_folder_id != null || ZohoOAuth.isProviderId(folder.accountProvider))
            return (EntityFolder.TRASH.equals(folder.type) && !folder.read_only &&
                    folder.zoho_folder_id != null && folder.zoho_folder_id.length() > 0 &&
                    ZohoApi.isValidRemoteId(folder.zoho_folder_id));

        return (folder.accountProtocol != null &&
                folder.accountProtocol == EntityAccount.TYPE_IMAP && !folder.read_only);
    }

    static boolean canEmptyFolder(EntityAccount account, EntityFolder folder) {
        if (!isSameSelectableFolder(account, folder))
            return false;

        if (account.isGmailApi())
            return GmailMailMutator.canExpungeFolder(true, folder.selectable,
                    folder.type, folder.gmail_label_id);
        if (account.isGraphApi())
            return (EntityFolder.TRASH.equals(folder.type) && !folder.read_only &&
                    folder.graph_folder_id != null && folder.graph_folder_id.length() > 0);
        if (account.isZohoApi())
            return ZohoMailMutator.canEmptyFolder(account, folder);

        return ((account.protocol == EntityAccount.TYPE_IMAP || account.protocol == EntityAccount.TYPE_POP) &&
                !folder.read_only &&
                (EntityFolder.TRASH.equals(folder.type) || EntityFolder.JUNK.equals(folder.type)));
    }

    static boolean canEmptyFolder(TupleFolderEx folder) {
        if (folder == null || folder.account == null || !folder.selectable)
            return false;

        if (folder.accountAuthType != null &&
                folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_GMAIL)
            return GmailMailMutator.canExpungeFolder(true, folder.selectable,
                    folder.type, folder.gmail_label_id);
        if (folder.accountAuthType != null &&
                folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_GRAPH_API)
            return (EntityFolder.TRASH.equals(folder.type) && !folder.read_only &&
                    folder.graph_folder_id != null && folder.graph_folder_id.length() > 0);
        if (folder.accountAuthType != null &&
                folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_ZOHO)
            return ZohoMailMutator.canEmptyFolder(folder);
        if (folder.gmail_label_id != null)
            return GmailMailMutator.canExpungeFolder(true, folder.selectable,
                    folder.type, folder.gmail_label_id);
        if (folder.graph_folder_id != null)
            return (EntityFolder.TRASH.equals(folder.type) && !folder.read_only &&
                    folder.graph_folder_id.length() > 0);
        if (folder.zoho_folder_id != null || ZohoOAuth.isProviderId(folder.accountProvider))
            return (EntityFolder.TRASH.equals(folder.type) && !folder.read_only &&
                    folder.zoho_folder_id != null && folder.zoho_folder_id.length() > 0 &&
                    ZohoApi.isValidRemoteId(folder.zoho_folder_id));

        return ((folder.accountProtocol != null &&
                (folder.accountProtocol == EntityAccount.TYPE_IMAP || folder.accountProtocol == EntityAccount.TYPE_POP)) &&
                !folder.read_only &&
                (EntityFolder.TRASH.equals(folder.type) || EntityFolder.JUNK.equals(folder.type)));
    }

    private static boolean isSameSelectableFolder(EntityAccount account, EntityFolder folder) {
        return (account != null && folder != null && folder.account != null &&
                account.id != null && account.id.equals(folder.account) && folder.selectable);
    }
}
