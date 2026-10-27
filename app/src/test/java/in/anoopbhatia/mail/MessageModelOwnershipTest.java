package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Regression tests for model ownership, replacement, and cached cleanup. */
public class MessageModelOwnershipTest {
    private static Harness harness;

    @BeforeClass
    public static void compileHarness() throws Exception {
        harness = new Harness();
    }

    @AfterClass
    public static void closeHarness() throws Exception {
        if (harness != null)
            harness.close();
    }

    @Test
    public void productionGetModelReusesEqualArgsAndObservesReturnedModel() throws Exception {
        String source = harness.productionSource();
        assertTrue(source.contains("if (model == null || !model.args.equals(args))"));
        assertTrue(source.contains("observeModel(owner, viewType, model);"));
    }

    @Test
    public void repeatedAcquisitionBySameOwnerDeduplicatesLifecycleRegistration() throws Exception {
        for (String viewType : new String[] {"THREAD", "SEARCH"}) {
            harness.install(viewType);
            harness.observe(viewType, "old", "a");
            harness.observe(viewType, "old", "a");
            assertEquals(1, harness.lifecycleObservers("a"));
            assertEquals(1, harness.snapshotObservers("old"));
        }
    }

    @Test
    public void replacementDoesNotLetOldOwnerCloseReplacement() throws Exception {
        for (String viewType : new String[] {"THREAD", "SEARCH"}) {
            harness.install(viewType);
            harness.observe(viewType, "old", "a");
            harness.replace(viewType, "new");
            harness.destroy("a");
            assertEquals(1, harness.closed("old"));
            assertEquals(0, harness.closed("new"));
            assertSame(harness.model("new"), harness.current(viewType));
        }
    }

    @Test
    public void replacementRemovesOldObserversWithoutTouchingNewObservers() throws Exception {
        for (String viewType : new String[] {"THREAD", "SEARCH"}) {
            harness.install(viewType);
            harness.observe(viewType, "old", "a");
            harness.replace(viewType, "new");
            harness.observe(viewType, "new", "b");
            harness.destroy("a");
            assertEquals(1, harness.lifecycleObservers("b"));
            assertEquals(1, harness.snapshotObservers("new"));
            assertEquals(0, harness.snapshotObservers("old"));
            assertEquals(0, harness.closed("new"));
        }
    }

    @Test
    public void sharedThreadModelSurvivesFirstOwnerInEitherOrder() throws Exception {
        for (String first : new String[] {"a", "b"}) {
            harness.install("THREAD");
            harness.observe("THREAD", "shared", "a");
            harness.observe("THREAD", "shared", "b");
            harness.destroy(first);
            assertEquals(0, harness.closed("shared"));
            assertSame(harness.model("shared"), harness.current("THREAD"));
            assertEquals(1, harness.snapshotObservers("shared"));
            harness.destroy(first.equals("a") ? "b" : "a");
            assertEquals(1, harness.closed("shared"));
            assertFalse(harness.hasCurrent("THREAD"));
        }
    }

    @Test
    public void lastOwnerRemovesCurrentModelAndClosesIt() throws Exception {
        for (String viewType : new String[] {"THREAD", "SEARCH"}) {
            harness.install(viewType);
            harness.observe(viewType, "old", "a");
            harness.destroy("a");
            assertEquals(1, harness.closed("old"));
            assertFalse(harness.hasCurrent(viewType));
        }
    }

    @Test
    public void searchHasSharedOwnerLastOwnerSemantics() throws Exception {
        harness.install("SEARCH");
        harness.observe("SEARCH", "search", "a");
        harness.observe("SEARCH", "search", "b");
        harness.destroy("b");
        assertEquals(0, harness.closed("search"));
        assertTrue(harness.hasCurrent("SEARCH"));
        harness.destroy("a");
        assertEquals(1, harness.closed("search"));
        assertFalse(harness.hasCurrent("SEARCH"));
    }

    @Test
    public void sameOwnerAcquiringOldThenNewCleansBothCapturedModels() throws Exception {
        for (String viewType : new String[] {"THREAD", "SEARCH"}) {
            harness.install(viewType);
            harness.observe(viewType, "old", "a");
            harness.replace(viewType, "new");
            harness.observe(viewType, "new", "a");
            harness.destroy("a");
            assertEquals(1, harness.closed("old"));
            assertEquals(1, harness.closed("new"));
            assertEquals(0, harness.snapshotObservers("old"));
            assertEquals(0, harness.snapshotObservers("new"));
        }
    }

    @Test
    public void unifiedOwnerCleanupKeepsCachedModelOpen() throws Exception {
        harness.install("UNIFIED");
        harness.observe("UNIFIED", "unified", "a");
        harness.destroy("a");
        assertEquals(0, harness.closed("unified"));
        assertTrue(harness.hasCurrent("UNIFIED"));
        assertEquals(0, harness.snapshotObservers("unified"));
    }

    @Test
    public void folderOwnerCleanupKeepsCachedModelOpen() throws Exception {
        harness.install("FOLDER");
        harness.observe("FOLDER", "folder", "a");
        harness.destroy("a");
        assertEquals(0, harness.closed("folder"));
        assertTrue(harness.hasCurrent("FOLDER"));
        assertEquals(0, harness.snapshotObservers("folder"));
    }

    @Test
    public void unifiedReplacementClosesCachedPreviousModelExactlyOnce() throws Exception {
        harness.install("UNIFIED");
        Object old = harness.current("UNIFIED");
        harness.replace("UNIFIED", "new");
        assertEquals(1, harness.closed("old"));
        assertEquals(0, harness.closed("new"));
        assertNotSame(old, harness.current("UNIFIED"));
    }

    @Test
    public void folderReplacementClosesCachedPreviousModelExactlyOnce() throws Exception {
        harness.install("FOLDER");
        Object old = harness.current("FOLDER");
        harness.replace("FOLDER", "new");
        assertEquals(1, harness.closed("old"));
        assertEquals(0, harness.closed("new"));
        assertNotSame(old, harness.current("FOLDER"));
    }

    @Test
    public void replacementClosesPreviousEvenWithoutOwnerDestruction() throws Exception {
        for (String viewType : new String[] {"THREAD", "SEARCH"}) {
            harness.install(viewType);
            harness.observe(viewType, "old", "a");
            harness.replace(viewType, "new");
            assertEquals(1, harness.closed("old"));
            assertEquals(0, harness.closed("new"));
            assertEquals(1, harness.snapshotObservers("old"));
        }
    }

    /** Compiles only the extracted production map, owner field, and callback. */
    private static final class Harness implements AutoCloseable {
        private final Path root;
        private final URLClassLoader loader;
        private final Class<?> type;

        Harness() throws Exception {
            root = Files.createTempDirectory("message-model-ownership-");
            try {
                String source = productionSource();
                String map = extractField(source, "    private Map<AdapterMessage.ViewType, Model> models =");
                String owners = extractField(source, "        private final Map<LifecycleOwner, Boolean> owners");
                String observer = extractMethod(source, "    private void observeModel(");
                if (!source.contains("model == null || !model.args.equals(args)"))
                    throw new IOException("production equal-argument reuse guard missing");
                if (!source.contains("observeModel(owner, viewType, model);"))
                    throw new IOException("production getModel observe hook missing");
                write(root, "in/anoopbhatia/mail/OwnershipHarness.java", template(map, owners, observer));
                Path classes = root.resolve("classes");
                Files.createDirectories(classes);
                List<String> command = new ArrayList<>();
                command.add(Path.of(System.getProperty("java.home"), "bin", "javac").toString());
                command.add("-d");
                command.add(classes.toString());
                try (var files = Files.walk(root)) {
                    files.filter(path -> path.toString().endsWith(".java")).forEach(path -> command.add(path.toString()));
                }
                Path log = root.resolve("javac.log");
                Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("standalone harness javac timed out");
                }
                if (process.exitValue() != 0)
                    throw new IOException("standalone harness javac failed:\n" + Files.readString(log, StandardCharsets.UTF_8));
                loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, null);
                type = loader.loadClass("in.anoopbhatia.mail.OwnershipHarness");
            } catch (Exception failure) {
                delete(root);
                throw failure;
            }
        }

        String productionSource() throws IOException {
            return Files.readString(projectFile("app/src/main/java/in/anoopbhatia/mail/ViewModelMessages.java"));
        }

        private Object call(String name, Object... arguments) throws Exception {
            Class<?>[] types = new Class<?>[arguments.length];
            for (int i = 0; i < arguments.length; i++)
                types[i] = arguments[i].getClass();
            return type.getMethod(name, types).invoke(null, arguments);
        }

        void install(String viewType) throws Exception { call("install", viewType); }
        void observe(String viewType, String model, String owner) throws Exception { call("observe", viewType, model, owner); }
        void replace(String viewType, String model) throws Exception { call("replace", viewType, model); }
        void destroy(String owner) throws Exception { call("destroy", owner); }
        int lifecycleObservers(String model) throws Exception { return (Integer) call("lifecycleObservers", model); }
        int snapshotObservers(String model) throws Exception { return (Integer) call("snapshotObservers", model); }
        int closed(String model) throws Exception { return (Integer) call("closed", model); }
        boolean hasCurrent(String viewType) throws Exception { return (Boolean) call("hasCurrent", viewType); }
        Object current(String viewType) throws Exception { return call("current", viewType); }
        Object model(String name) throws Exception { return call("model", name); }

        @Override public void close() throws Exception {
            loader.close();
            delete(root);
        }
    }

    private static String extractField(String source, String start) throws IOException {
        int begin = source.indexOf(start);
        if (begin < 0) throw new IOException("cannot extract production field: " + start);
        int brace = source.indexOf('{', begin);
        int lineEnd = source.indexOf('\n', begin);
        if (brace < 0 || (lineEnd >= 0 && brace > lineEnd)) {
            int end = source.indexOf(';', begin);
            if (end >= 0 && (lineEnd < 0 || end < lineEnd)) return source.substring(begin, end + 1);
            throw new IOException("unterminated production field: " + start);
        }
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            else if (source.charAt(i) == '}' && --depth == 0) {
                int end = source.indexOf(';', i);
                if (end >= 0) return source.substring(begin, end + 1);
                break;
            }
        }
        throw new IOException("unterminated production field: " + start);
    }

    private static String extractMethod(String source, String start) throws IOException {
        int begin = source.indexOf(start);
        if (begin < 0) throw new IOException("cannot extract production method: " + start);
        int brace = source.indexOf('{', begin);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            else if (source.charAt(i) == '}' && --depth == 0) return source.substring(begin, i + 1);
        }
        throw new IOException("unbalanced production method: " + start);
    }

    private static void write(Path root, String name, String content) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Path projectFile(String relative) throws IOException {
        for (Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath(); path != null; path = path.getParent()) {
            Path candidate = path.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IOException("cannot find project file: " + relative);
    }
    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList())
                Files.deleteIfExists(path);
        }
    }

    private static String template(String map, String owners, String observer) {
        return ("""
                package in.anoopbhatia.mail;
                import java.util.*;
                public class OwnershipHarness {
                    static final class AdapterMessage { enum ViewType { UNIFIED, FOLDER, THREAD, SEARCH } }
                    @interface Nullable { }
                    static final class Log { static void i(String message) { } }
                    interface LifecycleOwner { Lifecycle getLifecycle(); }
                    interface DefaultLifecycleObserver {
                        default void onPause(LifecycleOwner owner) { }
                        default void onResume(LifecycleOwner owner) { }
                        void onDestroy(LifecycleOwner owner);
                    }
                    static final class Lifecycle {
                        final List<DefaultLifecycleObserver> observers = new ArrayList<>();
                        void addObserver(DefaultLifecycleObserver observer) { observers.add(observer); }
                        void removeObserver(DefaultLifecycleObserver observer) { observers.remove(observer); }
                        void destroy(LifecycleOwner owner) { for (DefaultLifecycleObserver observer : new ArrayList<>(observers)) observer.onDestroy(owner); }
                    }
                    static final class Owner implements LifecycleOwner {
                        final Lifecycle lifecycle = new Lifecycle();
                        public Lifecycle getLifecycle() { return lifecycle; }
                    }
                    static final class SnapshotList {
                        final Set<LifecycleOwner> observers = Collections.newSetFromMap(new IdentityHashMap<LifecycleOwner, Boolean>());
                        void removeObservers(LifecycleOwner owner) { observers.remove(owner); }
                    }
                    static final class Args {
                        final String name;
                        Args(String name) { this.name = name; }
                        public boolean equals(Object other) { return other instanceof Args && name.equals(((Args) other).name); }
                    }
                    static final class Model {
                        final Args args;
                        final SnapshotList list = new SnapshotList();
                        <<OWNERS>>
                        int closeCount;
                        Model(String name) { args = new Args(name); }
                        void close() { closeCount++; }
                    }
                    <<MAP>>
                    <<OBSERVER>>
                    static final Map<String, Model> all = new HashMap<>();
                    static final Map<String, Owner> ownersByName = new HashMap<>();
                    static AdapterMessage.ViewType view(String value) { return AdapterMessage.ViewType.valueOf(value); }
                    static AdapterMessage.ViewType last = AdapterMessage.ViewType.UNIFIED;
                    static void dump() { }
                    public static void install(String value) {
                        models.clear(); all.clear(); ownersByName.clear();
                        Model model = new Model("old"); models.put(view(value), model); all.put("old", model);
                    }
                    public static void replace(String value, String name) {
                        Model model = new Model(name); models.put(view(value), model); all.put(name, model);
                    }
                    public static void observe(String value, String name, String ownerName) {
                        Model model = all.get(name);
                        if (model == null) {
                            model = models.get(view(value));
                            if (model == null) model = new Model(name);
                            all.put(name, model);
                        }
                        Owner owner = ownersByName.computeIfAbsent(ownerName, ignored -> new Owner());
                        boolean alreadyOwned = model.owners.containsKey(owner);
                        observeModel(owner, view(value), model);
                        if (!alreadyOwned) model.list.observers.add(owner);
                    }
                    public static void destroy(String ownerName) {
                        Owner owner = ownersByName.get(ownerName);
                        if (owner != null) owner.lifecycle.destroy(owner);
                    }
                    public static int lifecycleObservers(String name) { return ownersByName.get(name).lifecycle.observers.size(); }
                    public static int snapshotObservers(String name) { return all.get(name).list.observers.size(); }
                    public static int closed(String name) { return all.get(name).closeCount; }
                    public static boolean hasCurrent(String value) { return models.get(view(value)) != null; }
                    public static Model current(String value) { return models.get(view(value)); }
                    public static Model model(String name) { return all.get(name); }
                }
                """).replace("<<MAP>>", map.replace("private Map<", "private static Map<"))
                .replace("<<OWNERS>>", owners)
                .replace("<<OBSERVER>>", observer.replace("private void observeModel(", "private static void observeModel("));
    }
}
