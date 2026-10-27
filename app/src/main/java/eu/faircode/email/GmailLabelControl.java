package eu.faircode.email;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Maps Gmail custom-label IDs to user-visible names for message label controls. */
final class GmailLabelControl {
    static final class Choice {
        final String id;
        final String displayName;

        Choice(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }
    }

    private GmailLabelControl() {
    }

    static List<Choice> getUserLabelChoices(List<EntityFolder> folders, long accountId) {
        List<Choice> result = new ArrayList<>();
        if (folders == null)
            return result;

        for (EntityFolder folder : folders) {
            if (folder == null || !EntityFolder.USER.equals(folder.type) ||
                    folder.account == null || folder.account != accountId ||
                    !folder.selectable || empty(folder.gmail_label_id))
                continue;

            // GmailReceiver marks its label-folder rows read-only for the IMAP path;
            // Gmail message label controls write through the Gmail REST API instead.
            result.add(new Choice(folder.gmail_label_id, getDisplayName(folder)));
        }
        return result;
    }

    static String getDisplayName(EntityFolder folder) {
        if (folder == null)
            return "";
        String localName = GmailLabelFolder.getUserFolderName(folder.gmail_label_id);
        if (!empty(folder.display) && !folder.display.equals(localName))
            return folder.display;
        if (!empty(folder.name) && !folder.name.equals(localName))
            return folder.name;
        return (empty(folder.gmail_label_id) ? "" : folder.gmail_label_id);
    }

    static String[] getIds(List<Choice> choices) {
        String[] result = new String[choices == null ? 0 : choices.size()];
        for (int i = 0; i < result.length; i++)
            result[i] = choices.get(i).id;
        return result;
    }

    static String[] getDisplayNames(List<Choice> choices) {
        String[] result = new String[choices == null ? 0 : choices.size()];
        for (int i = 0; i < result.length; i++)
            result[i] = choices.get(i).displayName;
        return result;
    }

    static boolean[] getChecked(String[] labelIds, String[] selectedLabelIds) {
        boolean[] result = new boolean[labelIds == null ? 0 : labelIds.length];
        Set<String> selected = new HashSet<>();
        if (selectedLabelIds != null)
            for (String labelId : selectedLabelIds)
                if (!empty(labelId))
                    selected.add(labelId);
        for (int i = 0; i < result.length; i++)
            result[i] = selected.contains(labelIds[i]);
        return result;
    }

    private static boolean empty(String value) {
        return value == null || value.length() == 0;
    }
}
