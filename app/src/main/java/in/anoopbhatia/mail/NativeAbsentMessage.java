package in.anoopbhatia.mail;

import android.content.Context;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Retires a cached native REST message that its provider says no longer exists.
 *
 * <p>Only an explicit "this resource does not exist" answer may delete a row. Transport,
 * authentication, throttling, permission and parse failures keep the cached copy, because a
 * retry can still succeed. A provider ID that no longer resolves can never succeed, so the row
 * and its cached body/attachments are removed instead of being shown with a permanent error.</p>
 */
final class NativeAbsentMessage {
    private NativeAbsentMessage() {
    }

    static boolean isAbsent(Throwable ex) {
        Throwable cause = ex;
        // Provider exceptions are sometimes wrapped by the MIME/content layer.
        for (int depth = 0; cause != null && depth < 4; depth++) {
            if (cause instanceof GmailApi.ApiException)
                return isGone(((GmailApi.ApiException) cause).status);
            if (cause instanceof GraphMailApi.ApiException)
                return isGone(((GraphMailApi.ApiException) cause).status);
            if (cause instanceof ZohoApi.ApiException)
                return ((ZohoApi.ApiException) cause).isMessageAbsentFromFolder();
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean isGone(int status) {
        return status == 404 || status == 410;
    }

    /**
     * Deletes the cached row, its operations and attachments, then the cached files.
     *
     * <p>The row is deleted first and guarded by the same provider identity, so a concurrent
     * re-point of the row to another remote message can never be removed. Files that remain
     * after an interrupted delete are collected by the ordinary cleanup worker.</p>
     *
     * @return true when this call retired the message
     */
    static boolean purge(Context context, DB db, EntityMessage message, String reason) {
        if (context == null || db == null || message == null || message.id == null)
            return false;
        final String providerId = providerId(message);
        final List<EntityAttachment> attachments = db.attachment().getAttachments(message.id);
        boolean deleted = db.callInTransaction(() -> {
            EntityMessage current = db.message().getMessage(message.id);
            if (current == null || !Objects.equals(providerId, providerId(current)))
                return false;
            db.message().deleteMessage(current.id);
            return true;
        });
        if (!deleted)
            return false;
        deleteCachedFiles(context, message, attachments);
        EntityLog.log(context, EntityLog.Type.Account, message.account, message.folder, message.id,
                "Removed stale cached message; the provider reports it no longer exists (" +
                        reason + ")");
        // The row is gone, so the message list/search/navigation must be refreshed too.
        ServiceSynchronize.reload(context, message.account, false, "stale message removed");
        return true;
    }

    private static String providerId(EntityMessage message) {
        if (message.gmail_id != null)
            return "gmail:" + message.gmail_id;
        if (message.zoho_id != null)
            return "zoho:" + message.zoho_id;
        if (message.graph_id != null)
            return "graph:" + message.graph_id;
        return null;
    }

    static void deleteCachedFiles(Context context, EntityMessage message,
                                  List<EntityAttachment> attachments) {
        if (context == null || message == null || message.id == null)
            return;
        Helper.secureDelete(message.getFile(context));
        Helper.secureDelete(message.getRawFile(context));
        Helper.secureDelete(message.getRefFile(context));
        if (attachments != null)
            for (EntityAttachment attachment : attachments)
                if (attachment != null && attachment.id != null)
                    Helper.secureDelete(attachment.getFile(context));
        // Rendered inline images use "<message id>_<n>" / "<message id>.<n>" names.
        File[] images = Helper.ensureExists(context, "images").listFiles();
        if (images != null)
            for (File image : images)
                if (belongsToMessage(image.getName(), message.id))
                    Helper.secureDelete(image);
    }

    static boolean belongsToMessage(String name, long messageId) {
        if (name == null)
            return false;
        int end = 0;
        while (end < name.length() && Character.isDigit(name.charAt(end)))
            end++;
        if (end == 0)
            return false;
        if (end < name.length() && name.charAt(end) != '_' && name.charAt(end) != '.')
            return false;
        try {
            return Long.parseLong(name.substring(0, end)) == messageId;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
