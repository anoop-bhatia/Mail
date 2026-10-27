package eu.faircode.email;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailLabelControlTest {
    @Test
    public void restWritableUserLabelsIgnoreImapReadOnlyAndCarrySeparateIdAndDisplayName() {
        long accountId = 47L;
        EntityFolder project = userLabel(accountId, "Label_42", "Project Updates");
        project.read_only = true;
        EntityFolder billing = userLabel(accountId, "Label_84", "Billing");
        billing.read_only = true;
        EntityFolder wrongAccount = userLabel(accountId + 1, "Label_Foreign", "Foreign");
        EntityFolder notSelectable = userLabel(accountId, "Label_Hidden", "Hidden");
        notSelectable.selectable = false;
        EntityFolder noApiId = userLabel(accountId, null, "Missing ID");

        List<GmailLabelControl.Choice> choices = GmailLabelControl.getUserLabelChoices(
                Arrays.asList(project, billing, wrongAccount, notSelectable, noApiId), accountId);

        assertEquals(2, choices.size());
        assertEquals("Label_42", choices.get(0).id);
        assertEquals("Project Updates", choices.get(0).displayName);
        assertEquals("Label_84", choices.get(1).id);
        assertEquals("Billing", choices.get(1).displayName);
        assertArrayEquals(new String[]{"Label_42", "Label_84"}, GmailLabelControl.getIds(choices));
        assertArrayEquals(new String[]{"Project Updates", "Billing"},
                GmailLabelControl.getDisplayNames(choices));
        assertTrue(GmailMailMutator.isWritableUserLabelTarget(project, accountId));
    }

    @Test
    public void selectedStateUsesGmailLabelIdsAndNotVisibleNames() {
        String[] ids = {"Label_42", "Label_84"};
        String[] sentMessageLabels = {"SENT", "Label_42"};
        boolean[] checked = GmailLabelControl.getChecked(ids, sentMessageLabels);
        assertArrayEquals(new boolean[]{true, false}, checked);

        boolean[] namesAreNotIds = GmailLabelControl.getChecked(ids,
                new String[]{"SENT", "Project Updates"});
        assertFalse(namesAreNotIds[0]);
        assertFalse(namesAreNotIds[1]);
        assertTrue(GmailLabelFolder.isMember(sentMessageLabels, "SENT"));
    }

    private static EntityFolder userLabel(long accountId, String id, String display) {
        String name = (id == null ? "USER_missing" : GmailLabelFolder.getUserFolderName(id));
        EntityFolder folder = new EntityFolder(name, EntityFolder.USER);
        folder.account = accountId;
        folder.gmail_label_id = id;
        folder.display = display;
        folder.selectable = true;
        return folder;
    }
}
