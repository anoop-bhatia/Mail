package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SupportedBuildTypesTest {
    private static Path appDir() {
        Path current = Path.of("").toAbsolutePath();
        return Files.isDirectory(current.resolve("src/main")) ? current : current.resolve("app");
    }

    @Test
    public void supportedBuildsUseBuildTypesWithoutProductFlavors() throws Exception {
        String build = Files.readString(appDir().resolve("build.gradle.kts"));
        assertFalse(build.contains("productFlavors"));
        assertFalse(build.contains("flavorDimensions"));
        assertTrue(build.contains("getByName(\"release\")"));
        assertTrue(build.contains("getByName(\"debug\")"));
        assertTrue(build.contains("create(\"large\")"));
        assertTrue(build.contains("initWith(getByName(\"release\"))"));
        assertTrue(build.contains("matchingFallbacks += \"release\""));
        assertTrue(build.contains("manifestPlaceholders[\"largeHeap\"] = false"));
        assertTrue(build.contains("manifestPlaceholders[\"largeHeap\"] = true"));
        assertTrue(Set.of("release", "large", "debug").contains(BuildConfig.BUILD_TYPE));
    }

    @Test
    public void securityAndAuthorizationAreAvailableToEveryBuild() throws Exception {
        for (String name : new String[]{"ApplicationSecure", "GmailAuthorizationGms", "Avatar", "Bimi", "Misc", "Send", "VirusTotal"})
            assertTrue(name + " must be shared", Files.isRegularFile(appDir().resolve(
                    "src/main/java/in/anoopbhatia/mail/" + name + ".java")));
        String manifest = Files.readString(appDir().resolve("src/main/AndroidManifest.xml"));
        assertTrue(manifest.contains("android:name=\".ApplicationSecure\""));
        assertTrue(manifest.contains("android:name=\".ServiceTTS\""));
        assertTrue(manifest.contains("android:name=\".ServiceExternal\""));
        assertTrue(manifest.contains("@xml/network_security_config_ct"));
    }

    @Test
    public void largeRetainsReleaseOAuthRedirect() throws Exception {
        assertTrue(Files.readString(appDir().resolve("src/large/AndroidManifest.xml")).equals(
                Files.readString(appDir().resolve("src/release/AndroidManifest.xml"))));
    }
}
