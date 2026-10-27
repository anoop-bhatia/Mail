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
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FragmentDialogLabelsManage extends FragmentDialogBase {
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final long id = getArguments().getLong("id");
        final long[] ids = getArguments().getLongArray("ids");
        String self = getArguments().getString("self");
        String[] labels = getArguments().getStringArray("labels");
        final String[] folders = getArguments().getStringArray("folders");
        String[] display = getArguments().getStringArray("display");
        final String[] items = (display == null ? folders : display);
        boolean gmailApi = getArguments().getBoolean("gmail_api");

        boolean[] checked;
        if (gmailApi)
            checked = GmailLabelControl.getChecked(folders, labels);
        else {
            List<String> selected = new ArrayList<>();
            if (self != null)
                selected.add(self);
            if (labels != null)
                selected.addAll(Arrays.asList(labels));
            checked = new boolean[folders.length];
            for (int i = 0; i < folders.length; i++)
                checked[i] = selected.contains(folders[i]);
        }

        return new AlertDialog.Builder(getContext())
                .setIcon(R.drawable.twotone_label_24)
                .setTitle(R.string.title_manage_labels)
                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        if (gmailApi)
                            GmailMutationLog.event("picker_callback", EntityOperation.LABEL, "accepted");
                        Bundle args = new Bundle();
                        args.putLong("id", id);
                        args.putLongArray("ids", ids);
                        args.putString("label", folders[which]);
                        args.putBoolean("set", isChecked);
                        args.putBoolean("gmail_api", gmailApi);
                        args.putLong("account", getArguments().getLong("account", -1L));

                        new SimpleTask<Integer>() {
                            @Override
                            protected Integer onExecute(Context context, Bundle args) {
                                long id = args.getLong("id");
                                long[] ids = args.getLongArray("ids");
                                String label = args.getString("label");
                                boolean set = args.getBoolean("set");
                                boolean gmailApi = args.getBoolean("gmail_api");
                                long accountId = args.getLong("account", -1L);

                                DB db = DB.getInstance(context);
                                if (ids == null)
                                    ids = new long[]{id};
                                int skipped = 0;
                                for (long messageId : ids) {
                                    EntityMessage message = db.message().getMessage(messageId);
                                    if (gmailApi) {
                                        EntityAccount account = (message == null
                                                ? db.account().getAccount(accountId)
                                                : db.account().getAccount(message.account));
                                        EntityFolder source = (message == null ? null :
                                                db.folder().getFolder(message.folder));
                                        if (message == null || account == null || account.id != accountId ||
                                                !GmailMailMutator.isWritableMessage(account, source, message) ||
                                                !GmailMailMutator.isValidMessageLabelTarget(message)) {
                                            GmailMutationLog.event("picker_callback", EntityOperation.LABEL,
                                                    "source_missing");
                                            if (message != null && account != null && account.isGmailApi()) {
                                                db.message().setMessageUiHide(message.id, false);
                                                db.message().setMessageUiBusy(message.id, null);
                                            }
                                            skipped++;
                                        } else if (!GmailMailMutator.isManuallyApplicableLabel(db, account, label)) {
                                            GmailMutationLog.event("picker_callback", EntityOperation.LABEL,
                                                    "invalid_target");
                                            skipped++;
                                        } else if (!EntityOperation.queueGmailApiMutation(context, message,
                                                EntityOperation.LABEL, label, set)) {
                                            GmailMutationLog.event("picker_callback", EntityOperation.LABEL,
                                                    "not_queued");
                                            skipped++;
                                        }
                                    } else if (message != null)
                                        EntityOperation.queue(context, message, EntityOperation.LABEL, label, set);
                                }

                                if (gmailApi && skipped > 0 && accountId > 0)
                                    ServiceSynchronize.reload(context, accountId, false,
                                            "Gmail label source stale");
                                return skipped;
                            }

                            @Override
                            protected void onExecuted(Bundle args, Integer skipped) {
                                if (skipped != null && skipped > 0 && getContext() != null)
                                    ToastEx.makeText(getContext(), R.string.title_gmail_action_refresh,
                                            Toast.LENGTH_LONG).show();
                            }

                            @Override
                            protected void onException(Bundle args, Throwable ex) {
                                Log.unexpectedError(getParentFragmentManager(), ex);
                            }
                        }.execute(FragmentDialogLabelsManage.this, args, "label:set");
                    }
                })
                .setNegativeButton(R.string.title_setup_done, null)
                .create();
    }
}
