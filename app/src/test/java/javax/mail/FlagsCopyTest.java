package javax.mail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

import static org.junit.Assert.*;

public class FlagsCopyTest {
    private static Flags sample() {
        Flags flags = new Flags(Flags.Flag.SEEN);
        flags.add(Flags.Flag.USER);
        flags.add("Important");
        flags.add("Project");
        return flags;
    }

    private static void assertIndependent(Flags original, Flags copy) {
        assertNotSame(original, copy);
        assertEquals(original, copy);
        assertEquals(original.hashCode(), copy.hashCode());
        assertEquals(new HashSet<>(Arrays.asList("Important", "Project")),
                new HashSet<>(Arrays.asList(copy.getUserFlags())));
        copy.remove("IMPORTANT");
        copy.remove(Flags.Flag.SEEN);
        copy.add("CopyOnly");
        original.add("OriginalOnly");
        assertTrue(original.contains("important"));
        assertTrue(original.contains(Flags.Flag.SEEN));
        assertFalse(original.contains("CopyOnly"));
        assertFalse(copy.contains("OriginalOnly"));
        assertTrue(copy.contains(Flags.Flag.USER));
    }

    @Test
    public void copyConstructorPreservesFlagsAndOwnsItsTable() {
        Flags original = sample();
        assertIndependent(original, new Flags(original));
    }

    @Test
    public void clonePreservesFlagsAndOwnsItsTable() {
        Flags original = sample();
        assertIndependent(original, (Flags) original.clone());
    }

    @Test
    public void emptyAndClearedTablesRemainIndependent() {
        for (boolean useClone : new boolean[]{false, true}) {
            Flags original = new Flags();
            Flags copy = useClone ? (Flags) original.clone() : new Flags(original);
            assertEquals(original, copy);
            copy.add("New");
            assertFalse(original.contains("new"));
            original.add("Temporary");
            original.remove("Temporary");
            Flags cleared = useClone ? (Flags) original.clone() : new Flags(original);
            assertEquals(0, cleared.getUserFlags().length);
            cleared.add("Another");
            assertFalse(original.contains("another"));
        }
    }

    private static class ExtendedFlags extends Flags {
        private static final long serialVersionUID = 1L;
        final String extension = "retained";
    }

    @Test
    public void cloneRetainsRuntimeSubtypeAndSubclassFields() {
        ExtendedFlags original = new ExtendedFlags();
        original.add("Original");
        Object cloned = original.clone();
        assertTrue(cloned instanceof ExtendedFlags);
        ExtendedFlags copy = (ExtendedFlags) cloned;
        assertEquals("retained", copy.extension);
        copy.remove("Original");
        assertTrue(original.contains("Original"));
    }

    @Test
    public void copiesKeepEnglishCaseFoldingUnderTurkishDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Flags original = new Flags("IMPORTANT");
            assertTrue(new Flags(original).contains("important"));
            assertTrue(((Flags) original.clone()).contains("important"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void copiedFlagsRoundTripThroughExistingSerializedForm() throws Exception {
        Flags copy = new Flags(sample());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(copy);
        }
        Flags restored;
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Flags) input.readObject();
        }
        assertIndependent(copy, restored);
        assertEquals(6243590407214169028L,
                java.io.ObjectStreamClass.lookup(Flags.class).getSerialVersionUID());
    }
}
