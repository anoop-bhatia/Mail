package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source contracts only: platform span-array execution still requires Android device testing. */
public class SpannableStringBuilderExRegressionTest {
    private static String source() throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve("SpannableStringBuilderEx.java"));
    }

    @Test
    public void corruptFallbackDelegatesArrayCreationToFreshPlatformBuilder() throws Exception {
        String source = source();
        assertTrue(source.contains("return super.getSpans(queryStart, queryEnd, kind);"));
        assertTrue(source.contains("return new SpannableStringBuilder().getSpans(0, 0, kind);"));
        assertTrue(source.contains("catch (Throwable ex)"));
        assertTrue(source.contains("Log.e(ex);"));
    }

    @Test
    public void typedFallbackHasNoUncheckedCastOrSuppression() throws Exception {
        String source = source();
        assertFalse(source.contains("@SuppressWarnings"));
        assertFalse(source.contains("Array.newInstance"));
        assertFalse(source.contains("(T[])"));
    }
}
