package eu.faircode.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Current local UI state projected from Gmail's read-only message labelIds. */
final class GmailMessageLabelState {
    final List<String> labels;
    final boolean seen;
    final boolean flagged;
    final boolean deleted;
    final boolean answered;
    final Integer importance;

    private GmailMessageLabelState(List<String> labels) {
        this.labels = Collections.unmodifiableList(new ArrayList<>(labels));
        this.seen = !labels.contains("UNREAD");
        this.flagged = labels.contains("STARRED");
        this.deleted = labels.contains("TRASH");
        this.answered = labels.contains("ANSWERED");
        this.importance = (labels.contains("IMPORTANT")
                ? EntityMessage.PRIORITIY_HIGH : null);
    }

    static GmailMessageLabelState from(List<String> labels) {
        return new GmailMessageLabelState(labels == null
                ? Collections.<String>emptyList() : labels);
    }
}
