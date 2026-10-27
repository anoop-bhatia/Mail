package in.anoopbhatia.mail;

import androidx.room.InvalidationTracker;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Room285InvalidationRegressionTest {
    @Test
    public void workaroundTargetsAnActualFieldInTheResolvedRoomDependency() throws Exception {
        Field field = InvalidationTracker.class.getDeclaredField("viewTables");
        assertEquals(Map.class, field.getType());
        String source = Files.readString(Path.of("src/main/java/in/anoopbhatia/mail/DB.java"));
        assertTrue(source.contains("InvalidationTracker.class.getDeclaredField(\"viewTables\")"));
        assertFalse(source.contains("mViewTables"));
        assertTrue(source.contains("((Set<?>) tables).clear();"));
        assertTrue(source.contains("new String[]{\"account_view\", \"identity_view\", \"folder_view\"}"));
    }

    @Test
    public void reflectedFieldIsProtectedFromReleaseShrinking() throws Exception {
        String rules = Files.readString(Path.of("proguard-rules.pro"));
        assertTrue(rules.contains("-keepclassmembers class androidx.room.InvalidationTracker {\n" +
                "    java.util.Map viewTables;\n}"));
    }
}
