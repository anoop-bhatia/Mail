package eu.faircode.email;

import org.json.JSONArray;
import org.json.JSONException;

/** Shared routing rules for native API refreshes and user-requested older pages. */
final class NativeApiSyncRequest {
    static final int OLDER_PAGE_ARGUMENT = 6;

    private NativeApiSyncRequest() {
    }

    static boolean isOlderPageRequest(String args) {
        if (args == null || args.length() == 0)
            return false;
        try {
            return new JSONArray(args).optBoolean(OLDER_PAGE_ARGUMENT, false);
        } catch (JSONException ex) {
            return false;
        }
    }

    static String markOlderPageRequest(String args) {
        JSONArray jargs;
        try {
            jargs = (args == null ? new JSONArray() : new JSONArray(args));
        } catch (JSONException ex) {
            return args;
        }
        while (jargs.length() <= OLDER_PAGE_ARGUMENT)
            jargs.put(false);
        try {
            jargs.put(OLDER_PAGE_ARGUMENT, true);
        } catch (JSONException ex) {
            return args;
        }
        return jargs.toString();
    }

    static boolean isGmailOlderPageRequest(boolean gmailApi, String args) {
        return (gmailApi && isOlderPageRequest(args));
    }

    static boolean shouldProcessNativeFolderSync(boolean requested, boolean olderPage) {
        return (requested || olderPage);
    }

    static boolean shouldProcessGmailFolderSync(boolean requested, boolean olderPage) {
        return shouldProcessNativeFolderSync(requested, olderPage);
    }

    static boolean hasOlderMessages(EntityFolder folder, EntityAccount account) {
        if (folder == null || account == null || !account.isNativeRestApi())
            return true;
        // Gmail Fetch more pages message headers/references independently of the
        // eager body-download preference. Keep this gate for Zoho and Graph.
        if (account.isGmailApi())
            return GmailReceiver.canFetchMoreManually(folder.selectable,
                    folder.gmail_label_id, folder.gmail_page_token);
        if (!Boolean.TRUE.equals(folder.download))
            return false;
        if (account.isZohoApi())
            return ZohoReceiver.canFetchMoreManually(folder.selectable,
                    folder.zoho_folder_id, folder.zoho_page_start);
        if (account.isGraphApi())
            return GraphMailReceiver.canFetchMoreManually(folder.selectable,
                    folder.graph_folder_id, folder.graph_delta_link, folder.graph_page_link);
        return true;
    }
}
