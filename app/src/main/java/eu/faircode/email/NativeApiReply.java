package eu.faircode.email;

import java.util.List;

/** Resolves reply sources only from provider-native IDs attached to the source message. */
final class NativeApiReply {
    private NativeApiReply() {
    }

    static String gmailThreadId(List<EntityMessage> sources) {
        String value = null;
        if (sources != null)
            for (EntityMessage source : sources)
                if (source != null && source.gmail_id != null && !source.gmail_id.trim().isEmpty() &&
                        source.gmail_thread_id != null && !source.gmail_thread_id.trim().isEmpty()) {
                    if (value != null && !value.equals(source.gmail_thread_id))
                        return null;
                    value = source.gmail_thread_id;
                }
        return value;
    }

    static String zohoMessageId(List<EntityMessage> sources) {
        String value = null;
        if (sources != null)
            for (EntityMessage source : sources)
                if (source != null && ZohoApi.isValidRemoteId(source.zoho_id)) {
                    if (value != null && !value.equals(source.zoho_id))
                        return null;
                    value = source.zoho_id;
                }
        return value;
    }

    static String graphMessageId(List<EntityMessage> sources) {
        String value = null;
        if (sources != null)
            for (EntityMessage source : sources)
                if (source != null && source.graph_id != null && !source.graph_id.trim().isEmpty()) {
                    if (value != null && !value.equals(source.graph_id))
                        return null;
                    value = source.graph_id;
                }
        return value;
    }
}
