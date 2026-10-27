package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source contracts for the FragmentBase MenuProvider migration. */
public class FragmentMenuMigrationTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java/in/anoopbhatia/mail");

    @Test
    public void fragmentsUseAppMenuCallbacksInsteadOfFragmentMenuCallbacks() throws Exception {
        try (Stream<Path> files = Files.list(SOURCE_ROOT)) {
            files.filter(path -> path.getFileName().toString().startsWith("Fragment")
                            && path.getFileName().toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("extends FragmentBase");
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    })
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path);
                            assertFalse(path + " retains onCreateOptionsMenu",
                                    source.contains("onCreateOptionsMenu"));
                            assertFalse(path + " retains onPrepareOptionsMenu",
                                    source.contains("onPrepareOptionsMenu"));
                            assertFalse(path + " retains onOptionsItemSelected",
                                    source.contains("onOptionsItemSelected"));
                            assertFalse(path + " retains setHasOptionsMenu",
                                    source.contains("setHasOptionsMenu"));
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        }
    }

    @Test
    public void fragmentBaseKeepsResumedPaneExcludedProviderLifecycle() throws Exception {
        String source = Files.readString(SOURCE_ROOT.resolve("FragmentBase.java"));
        assertTrue(source.contains("if (!menuRequested || isPane() || menuProvider != null)"));
        assertTrue(source.contains("Lifecycle.State.RESUMED"));
        assertTrue(source.contains("removeMenuProvider(menuProvider)"));
        assertTrue(source.contains("onCreateAppMenu"));
        assertTrue(source.contains("onPrepareAppMenu"));
        assertTrue(source.contains("onAppMenuItemSelected"));
    }
}
