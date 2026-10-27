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

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/** Per-message availability of configurable toolbar buttons. */
final class MessageButtonCapabilities {
    enum Provider {
        LEGACY,
        GMAIL_API,
        GRAPH_API,
        ZOHO_API
    }

    /** Serializable snapshot passed from the message row to the button dialog. */
    static final class Context implements Serializable {
        private static final long serialVersionUID = 1L;

        Provider provider = Provider.LEGACY;
        boolean filtering;
        int protocol = EntityAccount.TYPE_IMAP;
        String folderType;
        boolean sourceWritable;
        boolean hasUid;
        boolean hasGmailId;
        boolean hasGraphId;
        boolean hasZohoId;
        boolean isPop;
        boolean gmailDraft;
        boolean gmailInInbox;
        boolean inTrash;
        boolean inJunk;
        boolean inArchive;
        boolean inSent;
        boolean outbox;
        boolean hasTrashTarget;
        boolean hasArchiveTarget;
        boolean hasJunkTarget;
        boolean hasMoveTarget;
        boolean hasHeaders;
        boolean hasRaw;
        boolean hasUnsubscribe;

        static Context unfiltered() {
            return new Context();
        }

        static Context unavailable() {
            Context context = new Context();
            context.filtering = true;
            return context;
        }
    }

    private MessageButtonCapabilities() {
    }

    static Context create(EntityAccount account, EntityMessage message,
                          EntityFolder source, List<EntityFolder> folders) {
        if (account == null || message == null || source == null)
            return Context.unavailable();

        Context context = new Context();
        context.filtering = true;
        context.protocol = account.protocol;
        context.provider = (account.isGmailApi() ? Provider.GMAIL_API :
                (account.isGraphApi() ? Provider.GRAPH_API :
                        (account.isZohoApi() ? Provider.ZOHO_API : Provider.LEGACY)));
        context.folderType = source.type;
        context.hasUid = (message.uid != null);
        context.isPop = (account.protocol == EntityAccount.TYPE_POP);
        context.hasGmailId = (message.gmail_id != null && message.gmail_id.length() > 0);
        context.hasGraphId = (message.graph_id != null && message.graph_id.length() > 0);
        context.hasZohoId = (message.zoho_id != null && message.zoho_id.length() > 0);
        context.gmailDraft = (EntityFolder.DRAFTS.equals(source.type) ||
                GmailLabelFolder.hasLabel(message.labels, "DRAFT"));
        context.gmailInInbox = GmailLabelFolder.hasLabel(message.labels, "INBOX");
        context.inTrash = EntityFolder.TRASH.equals(source.type);
        context.inJunk = EntityFolder.JUNK.equals(source.type);
        context.inArchive = EntityFolder.ARCHIVE.equals(source.type);
        context.inSent = EntityFolder.SENT.equals(source.type);
        context.outbox = EntityFolder.OUTBOX.equals(source.type);
        context.hasHeaders = (message.headers != null && message.headers.length() > 0);
        context.hasRaw = Boolean.TRUE.equals(message.raw);
        context.hasUnsubscribe = (message.unsubscribe != null);

        if (context.provider == Provider.GMAIL_API) {
            context.hasTrashTarget = hasGmailSystemFolder(folders, account.id, EntityFolder.TRASH);
            context.hasArchiveTarget = hasGmailSystemFolder(folders, account.id, EntityFolder.ARCHIVE);
            context.hasJunkTarget = hasGmailSystemFolder(folders, account.id, EntityFolder.JUNK);
        } else {
            context.hasTrashTarget = hasWritableSystemFolder(folders, EntityFolder.TRASH);
            context.hasArchiveTarget = hasWritableSystemFolder(folders, EntityFolder.ARCHIVE);
            context.hasJunkTarget = hasWritableSystemFolder(folders, EntityFolder.JUNK);
        }

        if (context.provider == Provider.GMAIL_API)
            context.sourceWritable = (context.hasGmailId && source.selectable &&
                    Objects.equals(source.account, account.id) && Objects.equals(message.account, account.id) &&
                    GmailMailMutator.isWritableMessage(account, source, message));
        else if (context.provider == Provider.GRAPH_API)
            context.sourceWritable = (context.hasGraphId && source.selectable && !source.read_only &&
                    Objects.equals(source.account, account.id) && Objects.equals(message.account, account.id) &&
                    !isEmpty(source.graph_folder_id));
        else if (context.provider == Provider.ZOHO_API)
            context.sourceWritable = (context.hasZohoId && ZohoMailMutator.isWritableMessage(account, source, message));
        else
            context.sourceWritable = (source.selectable && !source.read_only);

        context.hasMoveTarget = hasMoveTarget(account, message, source, folders, context.provider);
        return context;
    }

    private static boolean hasWritableSystemFolder(List<EntityFolder> folders, String type) {
        if (folders != null)
            for (EntityFolder folder : folders)
                if (type.equals(folder.type) && folder.selectable && !folder.read_only)
                    return true;
        return false;
    }

    private static boolean hasGmailSystemFolder(List<EntityFolder> folders, Long account, String type) {
        if (folders != null)
            for (EntityFolder folder : folders)
                if (type.equals(folder.type) && folder.selectable &&
                        Objects.equals(folder.account, account) && !isEmpty(folder.gmail_label_id))
                    return true;
        return false;
    }

    private static boolean hasMoveTarget(EntityAccount account, EntityMessage message,
                                         EntityFolder source, List<EntityFolder> folders,
                                         Provider provider) {
        if (folders == null)
            return false;
        for (EntityFolder target : folders) {
            if (target == null || Objects.equals(source.id, target.id))
                continue;
            if (provider == Provider.GMAIL_API) {
                if (GmailMailMutator.isValidMovePickerTarget(account, target) &&
                        GmailMailMutator.canMoveToTarget(account, source, message, target))
                    return true;
            } else if (provider == Provider.GRAPH_API) {
                if (Objects.equals(target.account, account.id) && target.selectable && !target.read_only &&
                        !isEmpty(target.graph_folder_id))
                    return true;
            } else if (provider == Provider.ZOHO_API) {
                if (ZohoMailMutator.isValidZohoMoveTarget(account, account, target, false))
                    return true;
            } else if (target.selectable && !target.read_only)
                return true;
        }
        return false;
    }

    private static boolean isEmpty(String value) {
        return (value == null || value.length() == 0);
    }

    static boolean isAvailable(String preference, Context context) {
        if (context == null || !context.filtering)
            return true;

        boolean nativeRest = (context.provider != Provider.LEGACY);
        boolean gmail = (context.provider == Provider.GMAIL_API && context.hasGmailId);
        boolean graph = (context.provider == Provider.GRAPH_API && context.hasGraphId);
        boolean zoho = (context.provider == Provider.ZOHO_API && context.hasZohoId);

        switch (preference) {
            case "button_junk":
                if (context.provider == Provider.GMAIL_API)
                    return (gmail && context.sourceWritable && context.hasJunkTarget && !context.inSent &&
                            !context.inJunk && !context.gmailDraft);
                if (context.provider == Provider.GRAPH_API)
                    return (graph && context.sourceWritable && context.hasJunkTarget && !context.inSent && !context.inJunk);
                if (context.provider == Provider.ZOHO_API)
                    return (zoho && context.sourceWritable && context.hasJunkTarget && !context.inSent && !context.inJunk);
                if (context.isPop)
                    return EntityFolder.INBOX.equals(context.folderType);
                return (!context.outbox && context.sourceWritable && context.hasUid &&
                        !context.inSent && !context.inJunk);

            case "button_trash":
                if (context.provider == Provider.GMAIL_API)
                    return (gmail && context.sourceWritable && !context.gmailDraft &&
                            (context.inTrash || context.hasTrashTarget));
                if (context.provider == Provider.GRAPH_API)
                    return (graph && context.sourceWritable && (context.inTrash || context.hasTrashTarget));
                if (context.provider == Provider.ZOHO_API)
                    return (zoho && context.sourceWritable);
                return (!context.outbox && (context.hasUid || context.isPop));

            case "button_archive":
                if (context.provider == Provider.GMAIL_API)
                    return (gmail && context.sourceWritable && !context.gmailDraft && !context.inSent &&
                            context.gmailInInbox && context.hasArchiveTarget && !context.inArchive &&
                            !context.inTrash && !context.inJunk);
                if (context.provider == Provider.GRAPH_API)
                    return (graph && context.sourceWritable && context.hasArchiveTarget && !context.inArchive &&
                            !context.inTrash && !context.inJunk);
                if (context.provider == Provider.ZOHO_API)
                    return (zoho && context.sourceWritable && context.hasArchiveTarget && !context.inArchive &&
                            !context.inTrash && !context.inJunk);
                return (!context.outbox && context.sourceWritable && context.hasUid &&
                        context.hasArchiveTarget && !context.inArchive && !context.inTrash && !context.inJunk);

            case "button_move":
                if (context.provider == Provider.GMAIL_API)
                    return (gmail && context.sourceWritable && !context.gmailDraft && !context.inSent &&
                            context.hasMoveTarget);
                if (context.provider == Provider.GRAPH_API)
                    return (graph && context.sourceWritable && context.hasMoveTarget);
                if (context.provider == Provider.ZOHO_API)
                    return (zoho && context.sourceWritable && context.hasMoveTarget);
                return (!context.outbox && context.sourceWritable && context.hasUid);

            case "button_copy":
                return (!nativeRest && !context.outbox && context.sourceWritable && context.hasUid);

            case "button_keywords":
                return (!nativeRest && !context.outbox && (context.hasUid || context.isPop));

            case "button_labels":
                return (gmail && context.sourceWritable && !context.gmailDraft &&
                        !context.inTrash && !context.inJunk && !context.outbox);

            case "button_seen":
                if (context.provider != Provider.LEGACY)
                    return ((gmail || graph || zoho) && context.sourceWritable && !context.outbox);
                return (!context.outbox && context.sourceWritable && (context.hasUid || context.isPop));

            case "button_importance":
                if (context.provider == Provider.GRAPH_API || context.provider == Provider.ZOHO_API)
                    return false;
                if (context.provider == Provider.GMAIL_API)
                    return (gmail && context.sourceWritable && !context.outbox);
                return (!context.outbox && context.sourceWritable && (context.hasUid || context.isPop));

            case "button_headers":
                return MessageSelectionCapabilities.canShowHeaders(context.hasUid, nativeRest,
                        context.isPop, context.hasHeaders ? "cached" : null);

            case "button_raw":
                if (nativeRest)
                    return (graph && context.hasRaw);
                return (context.hasUid || (context.isPop && EntityFolder.INBOX.equals(context.folderType)));

            case "button_unsubscribe":
                return context.hasUnsubscribe;

            case "button_rule":
                return (!context.outbox && context.sourceWritable);

            default:
                // The remaining entries are local/display/content actions, not provider mutations.
                return true;
        }
    }
}
