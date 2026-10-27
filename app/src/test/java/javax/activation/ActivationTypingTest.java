package javax.activation;

import com.sun.activation.registries.MimeTypeFile;
import org.junit.Test;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class ActivationTypingTest {
    @Test
    public void classTokensAndConstructorDefaultsArePreserved() throws Exception {
        ActivationDataFlavor explicit = new ActivationDataFlavor(String.class, "text/plain", "Text");
        Class<?> representation = explicit.getRepresentationClass();
        assertSame(String.class, representation);
        assertEquals("text/plain", explicit.getMimeType());
        assertEquals("Text", explicit.getHumanPresentableName());
        ActivationDataFlavor serialized = new ActivationDataFlavor(Set.class, "Set");
        assertSame(Set.class, serialized.getRepresentationClass());
        assertEquals("application/x-java-serialized-object", serialized.getMimeType());
        assertSame(InputStream.class,
                new ActivationDataFlavor("application/octet-stream", "Binary").getRepresentationClass());
        assertEquals(Class.class, ActivationDataFlavor.class.getMethod("getRepresentationClass").getReturnType());
        assertNotNull(ActivationDataFlavor.class.getConstructor(Class.class, String.class, String.class));
        assertNotNull(ActivationDataFlavor.class.getConstructor(Class.class, String.class));
    }

    @Test
    public void flavorEqualityStillUsesMimeMatchingAndClassIdentity() {
        ActivationDataFlavor flavor = new ActivationDataFlavor(String.class, "text/plain; charset=UTF-8", "Text");
        assertTrue(flavor.equals(new ActivationDataFlavor(String.class, "TEXT/PLAIN", "Another name")));
        assertFalse(flavor.equals(new ActivationDataFlavor(InputStream.class, "text/plain", "Text")));
        assertTrue(flavor.isMimeTypeEqual("text/*"));
        assertTrue(new ActivationDataFlavor(String.class, "invalid", "Invalid").isMimeTypeEqual("INVALID"));
    }

    @Test
    public void parameterValuesAndQuotingRoundTripThroughTypedMap() throws Exception {
        MimeTypeParameterList parameters = new MimeTypeParameterList("; Charset=UTF-8; title=\"a; b\\\"c\"; mode=plain");
        assertEquals("UTF-8", parameters.get(" CHARSET "));
        assertEquals("a; b\"c", parameters.get("title"));
        assertNull(parameters.get("missing"));
        parameters.set(" EXTRA ", "a\\b");
        MimeTypeParameterList reparsed = new MimeTypeParameterList(parameters.toString());
        assertEquals(4, reparsed.size());
        for (String name : Collections.list(parameters.getNames()))
            assertEquals(parameters.get(name), reparsed.get(name));
        assertEquals(new HashSet<>(Collections.list(parameters.getNames())),
                new HashSet<>(Collections.list(reparsed.getNames())));
        reparsed.remove(" MODE ");
        assertNull(reparsed.get("mode"));
    }

    @Test
    public void mimeFileAcceptsTraditionalAndKeyValueRecords() {
        MimeTypeFile file = new MimeTypeFile();
        file.appendToRegistry("text/x-first one two\n"
                + "type=application/x-second exts=\"three,four\"\n");
        assertEquals("text/x-first", file.getMIMETypeString("one"));
        assertEquals("text/x-first", file.getMIMETypeString("two"));
        assertEquals("application/x-second", file.getMIMETypeString("three"));
        assertEquals("application/x-second", file.getMIMETypeString("four"));
        assertNull(file.getMIMETypeString("missing"));
    }
}
