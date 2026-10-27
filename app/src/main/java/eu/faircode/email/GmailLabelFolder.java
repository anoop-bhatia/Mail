package eu.faircode.email;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gmail REST labels are message attributes, not exclusive mailboxes. These helpers
 * map selected labels to read-only local folders while keeping each Gmail message
 * in one database row.
 */
final class GmailLabelFolder {
    static final String ALL_MAIL_VIEW_ID = EntityFolder.GMAIL_API_ALL_MAIL_VIEW_ID;
    static final String ALL_MAIL_QUERY = "in:anywhere -in:spam -in:trash";
    // Compatibility aliases for operation/UI code that historically called this virtual row Archive.
    static final String ARCHIVE_VIEW_ID = ALL_MAIL_VIEW_ID;
    static final String ARCHIVE_QUERY = ALL_MAIL_QUERY;
    static final String USER_FOLDER_PREFIX = "__gmail_api_label__";

    // Gmail IDs are stored in EntityMessage.labels as URI-encoded, space-separated
    // tokens by DB.Converters. Label IDs used for folders are safe ASCII tokens.
    static final String SQL_MATCH_MESSAGE_FOLDER =
            "((message.account = folder.account AND message.gmail_id IS NOT NULL AND folder.gmail_label_id IS NOT NULL AND (" +
                    "(folder.gmail_label_id = '" + ALL_MAIL_VIEW_ID + "' AND " +
                    "instr(' ' || IFNULL(message.labels, '') || ' ', ' SPAM ') = 0 AND " +
                    "instr(' ' || IFNULL(message.labels, '') || ' ', ' TRASH ') = 0) OR " +
                    "(folder.gmail_label_id <> '" + ALL_MAIL_VIEW_ID + "' AND " +
                    "(folder.gmail_label_id = 'TRASH' OR " +
                    "instr(' ' || IFNULL(message.labels, '') || ' ', ' TRASH ') = 0) AND " +
                    "instr(' ' || IFNULL(message.labels, '') || ' ', ' ' || folder.gmail_label_id || ' ') > 0))) OR " +
                    "(message.account = folder.account AND message.zoho_id IS NOT NULL AND " +
                    "folder.zoho_folder_id IS NOT NULL AND " +
                    "instr(IFNULL(message.zoho_folder_ids, ''), '|' || folder.zoho_folder_id || '|') > 0))";

    static final String SQL_MATCH_MESSAGE_F =
            "((message.account = f.account AND message.gmail_id IS NOT NULL AND f.gmail_label_id IS NOT NULL AND (" +
                    "(f.gmail_label_id = '" + ALL_MAIL_VIEW_ID + "' AND " +
                    "instr(' ' || IFNULL(message.labels, '') || ' ', ' SPAM ') = 0 AND " +
                    "instr(' ' || IFNULL(message.labels, '') || ' ', ' TRASH ') = 0) OR " +
                    "(f.gmail_label_id <> '" + ALL_MAIL_VIEW_ID + "' AND " +
                    "(f.gmail_label_id = 'TRASH' OR " +
                    "instr(' ' || IFNULL(message.labels, '') || ' ', ' TRASH ') = 0) AND " +
                    "instr(' ' || IFNULL(message.labels, '') || ' ', ' ' || f.gmail_label_id || ' ') > 0))) OR " +
                    "(message.account = f.account AND message.zoho_id IS NOT NULL AND " +
                    "f.zoho_folder_id IS NOT NULL AND " +
                    "instr(IFNULL(message.zoho_folder_ids, ''), '|' || f.zoho_folder_id || '|') > 0))";

    // A message may still be in its original local folder after reauthorization. For
    // a Gmail label folder, count that physical row only if its current labels match.
    static final String SQL_MESSAGE_IN_FOLDER =
            "((message.folder = folder.id AND (message.gmail_id IS NULL OR " +
                    SQL_MATCH_MESSAGE_FOLDER + ")) OR " + SQL_MATCH_MESSAGE_FOLDER + ")";

    static final String SQL_MESSAGE_IN_TARGET_FOLDER =
            "((message.folder = :folder AND (message.gmail_id IS NULL OR " +
                    SQL_MATCH_MESSAGE_F + ")) OR " + SQL_MATCH_MESSAGE_F + ")";

    // Query-time display metadata for user labels: keep message.labels as stable API IDs,
    // but return the folder display name to the UI without performing database reads on bind.
    static final String SQL_LABEL_DISPLAY_DATA =
            "(SELECT GROUP_CONCAT(label_folder.gmail_label_id || ':' || " +
                    "hex(CAST(IFNULL(label_folder.display, '') AS BLOB)), ',') " +
                    "FROM folder_view AS label_folder WHERE label_folder.account = message.account " +
                    "AND label_folder.type = '" + EntityFolder.USER + "' " +
                    "AND label_folder.gmail_label_id IS NOT NULL AND " +
                    "instr(' ' || IFNULL(message.labels, '') || ' ', ' ' || label_folder.gmail_label_id || ' ') > 0)";

    private GmailLabelFolder() {
    }

    static String getFolderType(String id, String labelType) {
        if ("INBOX".equals(id))
            return EntityFolder.INBOX;
        if ("DRAFT".equals(id))
            return EntityFolder.DRAFTS;
        if ("SENT".equals(id))
            return EntityFolder.SENT;
        if ("TRASH".equals(id))
            return EntityFolder.TRASH;
        if ("SPAM".equals(id))
            return EntityFolder.JUNK;
        if ("USER".equalsIgnoreCase(labelType))
            return EntityFolder.USER;
        return null;
    }

    static List<String> getSystemLabelIds() {
        return Collections.unmodifiableList(Arrays.asList("INBOX", "DRAFT", "SENT", "TRASH", "SPAM"));
    }

    static void applyAllMailDefaults(EntityFolder folder, boolean initialize) {
        if (folder != null && initialize) {
            folder.synchronize = true;
            folder.navigation = true;
        }
    }

    static String getUserFolderName(String id) {
        return USER_FOLDER_PREFIX + id;
    }

    static String getUserFolderDisplayName(String current, String labelId,
                                           String localFolderName, String apiName) {
        String displayName = (apiName == null || apiName.length() == 0 ? labelId : apiName);
        if (current == null || current.length() == 0 ||
                (labelId != null && labelId.equals(current)) ||
                (localFolderName != null && localFolderName.equals(current)))
            return displayName;
        // A non-ID display value is a user customization; never replace it on refresh.
        return current;
    }

    static Map<String, String> parseLabelDisplayData(String data) {
        Map<String, String> result = new HashMap<>();
        if (data == null || data.length() == 0)
            return result;

        for (String pair : data.split(",")) {
            int separator = pair.indexOf(':');
            if (separator <= 0)
                continue;
            String id = pair.substring(0, separator);
            String hex = pair.substring(separator + 1);
            if ((hex.length() & 1) != 0)
                continue;
            byte[] bytes = new byte[hex.length() / 2];
            boolean valid = true;
            for (int i = 0; i < bytes.length; i++) {
                int high = Character.digit(hex.charAt(i * 2), 16);
                int low = Character.digit(hex.charAt(i * 2 + 1), 16);
                if (high < 0 || low < 0) {
                    valid = false;
                    break;
                }
                bytes[i] = (byte) ((high << 4) | low);
            }
            if (valid && bytes.length > 0)
                result.put(id, new String(bytes, StandardCharsets.UTF_8));
        }
        return result;
    }

    static String getDisplayLabel(String id, Map<String, String> names) {
        if (id == null || names == null)
            return id;
        String name = names.get(id);
        return (name == null || name.length() == 0 ? id : name);
    }

    static int getSystemFolderDisplayResource(String type) {
        if (EntityFolder.INBOX.equals(type))
            return R.string.title_folder_inbox;
        if (EntityFolder.DRAFTS.equals(type))
            return R.string.title_folder_drafts;
        if (EntityFolder.SENT.equals(type))
            return R.string.title_folder_sent;
        if (EntityFolder.TRASH.equals(type))
            return R.string.title_folder_trash;
        if (EntityFolder.JUNK.equals(type))
            return R.string.title_folder_junk;
        throw new IllegalArgumentException("Unsupported Gmail system folder type=" + type);
    }

    static String getFolderDisplayName(Context context, GmailApi.Label label, String type) {
        if (EntityFolder.USER.equals(type))
            return (label.name == null || label.name.length() == 0 ? label.id : label.name);
        return context.getString(getSystemFolderDisplayResource(type));
    }

    static boolean shouldLocalizeSystemDisplayName(String current, String labelId, String apiName) {
        return (current == null || current.length() == 0 || current.equals(labelId) ||
                current.equals(apiName));
    }

    static boolean isUserFolderName(String name) {
        return (name != null && name.startsWith(USER_FOLDER_PREFIX));
    }

    static boolean hasLabel(String[] labels, String id) {
        if (labels == null || id == null)
            return false;
        for (String label : labels)
            if (id.equals(label))
                return true;
        return false;
    }

    static boolean hasLabel(List<String> labels, String id) {
        return (labels != null && id != null && labels.contains(id));
    }

    static boolean isMember(String[] labels, String gmailLabelId) {
        if (ALL_MAIL_VIEW_ID.equals(gmailLabelId))
            return !hasLabel(labels, "SPAM") && !hasLabel(labels, "TRASH");
        if (!"TRASH".equals(gmailLabelId) && hasLabel(labels, "TRASH"))
            return false;
        return hasLabel(labels, gmailLabelId);
    }
}
