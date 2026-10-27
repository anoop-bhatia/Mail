package eu.faircode.email;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageButtonConfigurationTest {
    @Test
    public void baselineConfigureListIsOneGlobalOrderedActionSet() {
        List<String> baselineOrder = Arrays.asList(
                "button_junk", "button_trash", "button_archive", "button_move",
                "button_copy", "button_keywords", "button_labels", "button_notes",
                "button_seen", "button_hide", "button_importance", "button_translate",
                "button_tts", "button_summarize", "button_full_screen", "button_force_light",
                "button_search", "button_search_text", "button_event", "button_share",
                "button_pin", "button_print", "button_headers", "button_html",
                "button_raw", "button_unsubscribe", "button_rule", "button_answer");

        assertEquals("The dialog keeps the baseline checkbox order as one list",
                baselineOrder, MessageButtonConfiguration.PREFERENCES);
        assertFalse("There is no separate action-order preference",
                MessageButtonConfiguration.PREFERENCES.contains("button_order"));
        assertFalse("There is no separate action-limit preference",
                MessageButtonConfiguration.PREFERENCES.contains("button_limit"));
    }

    @Test
    public void providerFilteringCoversPrimaryAndAdditionalActionsInTheSameList() {
        MessageButtonCapabilities.Context gmail = context(MessageButtonCapabilities.Provider.GMAIL_API);
        gmail.hasHeaders = true;
        gmail.hasUnsubscribe = true;
        assertTrue(available("button_junk", gmail));
        assertTrue(available("button_trash", gmail));
        assertTrue(available("button_archive", gmail));
        assertTrue(available("button_move", gmail));
        assertTrue(available("button_labels", gmail));
        assertFalse(available("button_copy", gmail));
        assertFalse(available("button_keywords", gmail));
        assertFalse(available("button_raw", gmail));
        assertTrue("Cached message-header action remains in the same global list",
                available("button_headers", gmail));
        assertTrue("Unsubscribe is available when this message has a target",
                available("button_unsubscribe", gmail));
        assertTrue("Local display and message actions remain provider-independent",
                available("button_notes", gmail) && available("button_search_text", gmail) &&
                        available("button_share", gmail) && available("button_answer", gmail));

        MessageButtonCapabilities.Context graph = context(MessageButtonCapabilities.Provider.GRAPH_API);
        graph.hasHeaders = true;
        assertTrue(available("button_junk", graph));
        assertTrue(available("button_trash", graph));
        assertTrue(available("button_archive", graph));
        assertTrue(available("button_move", graph));
        assertFalse(available("button_labels", graph));
        assertFalse(available("button_importance", graph));
        assertFalse(available("button_copy", graph));
        assertFalse(available("button_keywords", graph));
        assertFalse(available("button_raw", graph));
        graph.hasRaw = true;
        assertTrue("Graph raw action depends on provider content being present",
                available("button_raw", graph));
        assertTrue(available("button_headers", graph));
        assertTrue(available("button_notes", graph));

        MessageButtonCapabilities.Context gmailHostImap = context(MessageButtonCapabilities.Provider.LEGACY);
        assertFalse("Gmail-host IMAP retains legacy IMAP, not Gmail API, labels",
                available("button_labels", gmailHostImap));
        assertTrue(available("button_copy", gmailHostImap));
        assertTrue(available("button_keywords", gmailHostImap));
    }

    @Test
    public void savingVisibleChoicesLeavesHiddenGlobalValuesUntouched() {
        MessageButtonCapabilities.Context gmail = context(MessageButtonCapabilities.Provider.GMAIL_API);
        Set<String> visible = new HashSet<>();
        for (String preference : MessageButtonConfiguration.PREFERENCES)
            if (available(preference, gmail))
                visible.add(preference);

        Map<String, Boolean> existing = new HashMap<>();
        existing.put("button_trash", true);
        existing.put("button_labels", false);
        existing.put("button_copy", true);
        existing.put("button_keywords", true);
        existing.put("button_raw", true);
        existing.put("button_importance", true);

        Map<String, Boolean> selections = new HashMap<>();
        selections.put("button_trash", false);
        selections.put("button_labels", true);
        selections.put("button_copy", false);
        selections.put("button_keywords", false);
        selections.put("button_raw", false);
        selections.put("button_importance", false);

        Map<String, Boolean> updates = MessageButtonConfiguration.visibleSelections(selections, visible);
        existing.putAll(updates);

        assertFalse("Visible Gmail Trash choice is saved", existing.get("button_trash"));
        assertTrue("Visible Gmail Labels choice is saved", existing.get("button_labels"));
        assertTrue("Unsupported Copy preference survives opening/saving in Gmail API context",
                existing.get("button_copy"));
        assertTrue("Unsupported Keywords preference survives opening/saving in Gmail API context",
                existing.get("button_keywords"));
        assertTrue("Unavailable raw action preference survives opening/saving in Gmail API context",
                existing.get("button_raw"));
        assertFalse("Visible Importance choice is saved", existing.get("button_importance"));
        assertFalse("Hidden preferences are not part of the update set",
                updates.containsKey("button_copy") || updates.containsKey("button_keywords") ||
                        updates.containsKey("button_raw"));
    }

    private static MessageButtonCapabilities.Context context(MessageButtonCapabilities.Provider provider) {
        MessageButtonCapabilities.Context context = new MessageButtonCapabilities.Context();
        context.filtering = true;
        context.provider = provider;
        context.protocol = EntityAccount.TYPE_IMAP;
        context.folderType = EntityFolder.INBOX;
        context.sourceWritable = true;
        context.hasUid = (provider == MessageButtonCapabilities.Provider.LEGACY);
        context.hasGmailId = (provider == MessageButtonCapabilities.Provider.GMAIL_API);
        context.hasGraphId = (provider == MessageButtonCapabilities.Provider.GRAPH_API);
        context.hasZohoId = (provider == MessageButtonCapabilities.Provider.ZOHO_API);
        context.gmailInInbox = true;
        context.hasTrashTarget = true;
        context.hasArchiveTarget = true;
        context.hasJunkTarget = true;
        context.hasMoveTarget = true;
        return context;
    }

    private static boolean available(String preference, MessageButtonCapabilities.Context context) {
        return MessageButtonCapabilities.isAvailable(preference, context);
    }
}
