package eu.faircode.email;

import android.content.Context;
import android.os.Bundle;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LifecycleOwner;
import java.util.ArrayList;
import java.util.List;

/** Shared Fetch more entry handler for folder, message-list and unified-inbox actions. */
final class FetchMoreAction {
    static final String ARG_LEGACY_FOLDERS = "fetch_more_legacy_folders";

    private FetchMoreAction() {
    }

    static void open(Context context, LifecycleOwner owner, FragmentManager manager,
                     Bundle args, String dialogTag) {
        new SimpleTask<long[]>() {
            @Override
            protected long[] onExecute(Context context, Bundle args) throws Throwable {
                return prepare(context, args);
            }

            @Override
            protected void onExecuted(Bundle args, long[] legacyFolderIds) {
                if (legacyFolderIds == null || legacyFolderIds.length == 0)
                    return;

                Bundle chooserArgs = new Bundle(args);
                chooserArgs.putLongArray(ARG_LEGACY_FOLDERS, legacyFolderIds);
                FragmentDialogSync sync = new FragmentDialogSync();
                sync.setArguments(chooserArgs);
                sync.show(manager, dialogTag);
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                Log.unexpectedError(manager, ex);
            }
        }.execute(context, owner, args, "fetch:more");
    }

    private static long[] prepare(Context context, Bundle args) {
        DB db = DB.getInstance(context);
        long folderId = args.getLong("folder", -1L);
        String type = args.getString("type");
        List<EntityFolder> folders = new ArrayList<>();
        if (folderId < 0) {
            List<EntityFolder> unified = db.folder().getFoldersUnified(type, false);
            if (unified != null)
                folders.addAll(unified);
        } else {
            EntityFolder folder = db.folder().getFolder(folderId);
            if (folder != null) {
                args.putString("name", folder.getDisplayName(context));
                folders.add(folder);
                if (args.getBoolean("children")) {
                    List<EntityFolder> children = EntityFolder.getChildFolders(context, folder.id);
                    if (children != null)
                        folders.addAll(children);
                }
            }
        }

        ArrayList<FetchMoreRouting.Target> targets = new ArrayList<>();
        for (EntityFolder folder : folders)
            if (folder != null && folder.selectable) {
                EntityAccount account = (folder.account == null ? null : db.account().getAccount(folder.account));
                targets.add(FetchMoreRouting.Target.create(folder, account));
            }

        FetchMoreRouting.Result result = FetchMoreRouting.dispatch(targets, new FetchMoreRouting.PageRequester() {
            @Override
            public void requestOlderPage(long folderId, int pageSize) {
                if (pageSize != FetchMoreRouting.NATIVE_PAGE_SIZE)
                    throw new IllegalArgumentException("Unexpected native older-page size=" + pageSize);
                verifyNativePageSizes();
                EntityOperation.syncMore(context, folderId, true);
            }
        });
        if (result.nativePagesRequested > 0)
            ServiceSynchronize.eval(context, "fetch:more");

        long[] legacyFolderIds = new long[result.legacyFolderIds.size()];
        for (int i = 0; i < result.legacyFolderIds.size(); i++)
            legacyFolderIds[i] = result.legacyFolderIds.get(i);
        return legacyFolderIds;
    }

    private static void verifyNativePageSizes() {
        int expected = FetchMoreRouting.NATIVE_PAGE_SIZE;
        if (GmailReceiver.PAGE_SIZE != expected ||
                ZohoReceiver.PAGE_SIZE != expected ||
                GraphMailReceiver.PAGE_SIZE != expected)
            throw new IllegalStateException("Native Fetch more must request exactly " + expected + " messages per provider page");
    }
}
