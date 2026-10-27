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

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

public class FragmentDialogSync extends FragmentDialogBase {
    private static final int DEFAULT_KEEP = 3; // months

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        long fid = args.getLong("folder");
        String name = args.getString("name");
        String type = args.getString("type");

        final Context context = getContext();
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_sync, null);
        final TextView tvFolder = view.findViewById(R.id.tvFolder);
        final EditText etMonths = view.findViewById(R.id.etMonths);
        final TextView tvRemark = view.findViewById(R.id.tvRemark);

        String key;
        if (fid < 0) {
            key = "default_keep" + (TextUtils.isEmpty(type) ? "" : "." + type);
            if (TextUtils.isEmpty(type))
                tvFolder.setText(R.string.title_folder_unified);
            else
                tvFolder.setText(EntityFolder.localizeType(context, type));
        } else {
            key = "default_keep." + fid;
            tvFolder.setText(name);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int def = prefs.getInt(key, DEFAULT_KEEP);
        etMonths.setText(def < 0 ? null : Integer.toString(def));

        tvRemark.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Helper.viewFAQ(view.getContext(), 39);
            }
        });

        return new AlertDialog.Builder(context)
                .setView(view)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String months = etMonths.getText().toString();

                        Bundle args = getArguments();
                        if (TextUtils.isEmpty(months)) {
                            prefs.edit().putInt(key, -1).apply();
                            args.putInt("months", 0);
                        } else
                            try {
                                int m = Integer.parseInt(months);
                                prefs.edit().putInt(key, m).apply();
                                args.putInt("months", m);
                            } catch (NumberFormatException ex) {
                                Log.e(ex);
                                return;
                            }

                        new SimpleTask<Void>() {
                            @Override
                            protected Void onExecute(Context context, Bundle args) {
                                long fid = args.getLong("folder");
                                String type = args.getString("type");
                                int months = args.getInt("months", -1);
                                boolean children = args.getBoolean("children");

                                DB db = DB.getInstance(context);
                                try {
                                    db.beginTransaction();

                                    List<EntityFolder> folders = new ArrayList<>();
                                    long[] selected = args.getLongArray(FetchMoreAction.ARG_LEGACY_FOLDERS);
                                    if (selected != null) {
                                        for (long selectedId : selected) {
                                            EntityFolder folder = db.folder().getFolder(selectedId);
                                            if (folder != null)
                                                folders.add(folder);
                                        }
                                    } else if (fid < 0) {
                                        List<EntityFolder> unified = db.folder().getFoldersUnified(type, false);
                                        if (unified != null)
                                            folders.addAll(unified);
                                    } else {
                                        EntityFolder folder = db.folder().getFolder(fid);
                                        if (folder == null)
                                            return null;

                                        folders.add(folder);

                                        if (children) {
                                            List<EntityFolder> sub = EntityFolder.getChildFolders(context, folder.id);
                                            folders.addAll(sub);
                                        }
                                    }

                                    for (EntityFolder folder : folders)
                                        if (folder.selectable) {
                                            EntityAccount account = (folder.account == null ? null : db.account().getAccount(folder.account));
                                            if (account != null && account.isNativeRestApi()) {
                                                EntityOperation.syncMore(context, folder.id, true);
                                                continue;
                                            }

                                            Integer days = FetchMoreRouting.getKeepDays(months);
                                            if (days != null) {
                                                db.folder().setFolderInitialize(folder.id, days);
                                                db.folder().setFolderKeep(folder.id, days);
                                            }
                                            EntityOperation.syncMore(context, folder.id, true);
                                        }

                                    db.setTransactionSuccessful();
                                } finally {
                                    db.endTransaction();
                                }

                                ServiceSynchronize.eval(context, "folder:months");

                                return null;
                            }

                            @Override
                            protected void onException(Bundle args, Throwable ex) {
                                Log.unexpectedError(getParentFragmentManager(), ex);
                            }
                        }.execute(getContext(), getViewLifecycleOwner(), args, "folder:months");
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }
}
