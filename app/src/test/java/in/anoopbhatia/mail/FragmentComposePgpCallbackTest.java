package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class FragmentComposePgpCallbackTest {
    @Test
    public void successfulActionInitializesMissingExtrasBeforeMarkingEncrypted() throws Exception {
        String source = source();
        int callback = source.indexOf("Log.i(\"Result= \" + result);");
        int resultNull = source.indexOf("if (result == null)", callback);
        int readExtras = source.indexOf("Bundle extras = args.getBundle(\"extras\");", resultNull);
        int nullCheck = source.indexOf("if (extras == null)", readExtras);
        int initialize = source.indexOf("extras = new Bundle();", nullCheck);
        int markEncrypted = source.indexOf("extras.putBoolean(\"encrypted\", true);", initialize);
        int dispatch = source.indexOf("onAction(action, extras, \"pgp\");", markEncrypted);

        assertTrue("OpenPGP callback and result branch must exist", callback >= 0 && resultNull > callback);
        assertTrue("Missing extras must be initialized before use",
                readExtras > resultNull && nullCheck > readExtras && initialize > nullCheck &&
                        markEncrypted > initialize && dispatch > markEncrypted);
    }

    private static String source() throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve("FragmentCompose.java"));
    }
}
