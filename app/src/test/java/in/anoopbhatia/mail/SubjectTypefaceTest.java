package in.anoopbhatia.mail;

import android.graphics.Typeface;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SubjectTypefaceTest {
    @Test
    public void ordinarySubjectUsesNormalStyle() {
        assertEquals(Typeface.NORMAL, AdapterMessage.SubjectTypeface.resolve(false, true));
    }

    @Test
    public void italicPreferenceIsPreserved() {
        assertEquals(Typeface.ITALIC, AdapterMessage.SubjectTypeface.resolve(true, true));
    }

    @Test
    public void mixedScriptSubjectRetainsBoldWarning() {
        assertEquals(Typeface.BOLD, AdapterMessage.SubjectTypeface.resolve(false, false));
    }

    @Test
    public void mixedScriptWarningAndItalicPreferenceAreCombined() {
        assertEquals(Typeface.BOLD_ITALIC, AdapterMessage.SubjectTypeface.resolve(true, false));
    }
}
