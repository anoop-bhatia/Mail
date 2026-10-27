package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source contracts for framework calls which the local JVM Android stubs cannot execute. */
public class ActivityBaseTaskDescriptionTest {
    private static String source() throws Exception {
        return Files.readString(Paths.get("src/main/java/in/anoopbhatia/mail/ActivityBase.java"),
                StandardCharsets.UTF_8);
    }

    @Test
    public void customizationIsOptInAndGuardedAtBuilderIntroduction() throws Exception {
        String source = compact(source());
        int guard = source.indexOf("if (task_description && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {");
        int apply = source.indexOf("setTaskDescription(createTaskDescription(colorPrimary));", guard);
        int end = source.indexOf("} catch (Throwable ex)", guard);
        assertTrue("API 26-32 must retain the system default", guard >= 0);
        assertTrue("Customization must stay inside the API 33 guard", apply > guard && apply < end);
        assertTrue(source.contains("@RequiresApi(Build.VERSION_CODES.TIRAMISU) private static ActivityManager.TaskDescription createTaskDescription(int color)"));
    }

    @Test
    public void builderUsesApi33ResourceOverloadWithoutDeprecatedFallback() throws Exception {
        String source = compact(source());
        assertTrue(source.contains("int icon = ColorUtils.calculateLuminance(color) > LUMINANCE_THRESHOLD"));
        assertTrue(source.contains("? R.drawable.task_mail_black : R.drawable.task_mail_white;"));
        assertTrue(source.contains("new ActivityManager.TaskDescription.Builder() .setIcon(icon) .setPrimaryColor(color) .build()"));
        assertFalse(source.contains("new ActivityManager.TaskDescription("));
        assertFalse(source.contains("createLegacyTaskDescription"));
        assertFalse(source.contains("Icon.createWithBitmap"));
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    @Test
    public void resourceIconsKeepContrastWithoutThemeDependentTint() throws Exception {
        for (String color : new String[]{"black", "white"}) {
            String icon = Files.readString(Paths.get("src/main/res/drawable/task_mail_" + color + ".xml"),
                    StandardCharsets.UTF_8);
            assertTrue(icon.contains("android:fillColor=\"@android:color/" + color + "\""));
            assertFalse(icon.contains("android:tint"));
        }
    }
}
