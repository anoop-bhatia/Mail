package eu.faircode.email;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared global preference model for the complete message action flow. */
final class MessageButtonConfiguration {
    // Keep the baseline dialog order as one list: primary and less-frequently
    // used actions are not separate settings or preference namespaces.
    static final List<String> PREFERENCES = Collections.unmodifiableList(Arrays.asList(
            "button_junk",
            "button_trash",
            "button_archive",
            "button_move",
            "button_copy",
            "button_keywords",
            "button_labels",
            "button_notes",
            "button_seen",
            "button_hide",
            "button_importance",
            "button_translate",
            "button_tts",
            "button_summarize",
            "button_full_screen",
            "button_force_light",
            "button_search",
            "button_search_text",
            "button_event",
            "button_share",
            "button_pin",
            "button_print",
            "button_headers",
            "button_html",
            "button_raw",
            "button_unsubscribe",
            "button_rule",
            "button_answer"));

    private MessageButtonConfiguration() {
    }

    /** Return updates only for visible checkboxes, preserving hidden global values. */
    static Map<String, Boolean> visibleSelections(Map<String, Boolean> selections,
                                                   Set<String> visible) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String preference : PREFERENCES)
            if (visible.contains(preference) && selections.containsKey(preference))
                result.put(preference, selections.get(preference));
        return result;
    }
}
