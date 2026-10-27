package in.anoopbhatia.mail;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Source contract guards; Android lifecycle/registry delivery still needs device testing. */
public class ActivityResultRoutingSourceTest {
    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/in/anoopbhatia/mail", file));
    }

    @Test
    public void routerUsesIndependentStableLifecycleRegistrationsRatherThanFifo() throws Exception {
        String router = source("ActivityResultRouter.java");
        assertTrue(router.contains("keyPrefix + \":activity-result:\" + requestCode"));
        assertTrue(router.contains("registry.register(key, registration,"));
        assertTrue(router.contains("result -> callback.onActivityResult(requestCode, result)"));
        assertTrue(router.contains("Lifecycle.State.STARTED : Lifecycle.State.CREATED"));
        assertFalse(router.contains("removeFirst"));
        assertFalse(router.contains("ArrayDeque"));
        assertFalse(router.contains("Handler"));
        assertFalse(router.contains("SuppressWarnings"));
    }

    @Test
    public void registrationsSurviveMultipleSameCodeResultsAndAreSavedAndClosed() throws Exception {
        String router = source("ActivityResultRouter.java");
        assertTrue(router.contains("state.putIntArray(KEY, requests)"));
        assertTrue(router.contains("state.getIntArray(KEY)"));
        assertTrue(router.contains("for (int request : registrations.keySet())"));
        assertTrue(router.contains("registration.launcher.unregister()"));
        assertTrue(router.contains("Lifecycle.Event.ON_DESTROY"));
        String callback = router.substring(router.indexOf("registration.launcher = registry.register"),
                router.indexOf("registrations.put(requestCode, registration)"));
        assertFalse(callback.contains("unregister"));
        assertFalse(callback.contains("registrations.remove"));
    }

    @Test
    public void ownersRestoreSaveAndCloseWithoutDeprecatedActivityOrTargetOverrides() throws Exception {
        for (String file : new String[]{"ActivityBase.java", "FragmentBase.java", "FragmentDialogBase.java"}) {
            String text = source(file);
            assertTrue(file, text.contains("activityResultRouter.restore(savedInstanceState)"));
            assertTrue(file, text.contains("activityResultRouter.save(outState)"));
            assertTrue(file, text.contains("activityResultRouter.close()"));
            assertTrue(file, text.contains("activityResultRouter.launch(intent, requestCode)"));
            assertFalse(file, text.contains("void startActivityForResult("));
            assertFalse(file, text.contains("void onActivityResult("));
            assertFalse(file, text.contains("activityResultRequestCodes"));
        }
        assertFalse(source("FragmentDialogBase.java").contains("void setTargetFragment("));
        assertTrue(source("FragmentDialogBase.java").contains("void setResultTarget("));
    }
}
