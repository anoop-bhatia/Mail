package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Regression contracts for the API-30-only ControlsProviderService. */
public class ServicePowerControlApiTest {
    private static String read(String file) throws Exception {
        Path path = Path.of(file);
        if (!Files.isRegularFile(path))
            path = Path.of("app").resolve(file);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    public void serviceIsDeclaredDisabledByDefaultInManifestSources() throws Exception {
        for (String file : new String[]{
                "src/main/AndroidManifest.xml",
                "src/debug/AndroidManifest.xml"}) {
            String manifest = read(file);
            String declaration = manifest.substring(
                    manifest.indexOf("android:name=\".ServicePowerControl\""),
                    manifest.indexOf("</service>", manifest.indexOf("android:name=\".ServicePowerControl\"")));
            assertTrue(file, declaration.contains("tools:targetApi=\"30\""));
            assertTrue(file, declaration.contains("android:enabled=\"false\""));
        }
    }

    @Test
    public void componentEnablementIsGuardedByApi30() throws Exception {
        String source = read("src/main/java/in/anoopbhatia/mail/FragmentOptionsMisc.java").replaceAll("\\s+", " ");
        String call = "Helper.enableComponent(getContext(), ServicePowerControl.class, checked);";
        String reset = "Helper.enableComponent(context, ServicePowerControl.class, false);";
        assertEquals(1, count(source, call));
        assertEquals(1, count(source, reset));
        assertTrue(source.contains("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) " + call));
        assertTrue(source.contains("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) " + reset));
    }

    private static int count(String source, String value) {
        int count = 0;
        for (int at = source.indexOf(value); at >= 0; at = source.indexOf(value, at + value.length()))
            count++;
        return count;
    }
}
