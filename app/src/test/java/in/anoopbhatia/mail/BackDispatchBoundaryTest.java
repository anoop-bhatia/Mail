package in.anoopbhatia.mail;

import static org.junit.Assert.*;

import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Runs the pinned AndroidX dispatcher and lifecycle, not dispatcher doubles.
 * Gate is the REJECTED self-disabling redispatch proposal, not production code.
 * These tests document why it cannot replace ActivityBase's outer catch boundary.
 * Host tests do not exercise platform input registration or device animations.
 */
public class BackDispatchBoundaryTest {
    private static final class Owner implements LifecycleOwner {
        final LifecycleRegistry lifecycle = LifecycleRegistry.createUnsafe(this);
        @Override public Lifecycle getLifecycle() { return lifecycle; }
        void start() { lifecycle.setCurrentState(Lifecycle.State.STARTED); }
        void stop() { lifecycle.setCurrentState(Lifecycle.State.CREATED); }
        void destroy() { lifecycle.setCurrentState(Lifecycle.State.DESTROYED); }
    }

    private static final class Gate extends OnBackPressedCallback {
        final OnBackPressedDispatcher dispatcher;
        final List<Throwable> caught = new ArrayList<>();
        int calls;
        Gate(OnBackPressedDispatcher dispatcher) { super(true); this.dispatcher = dispatcher; }
        @Override public void handleOnBackPressed() {
            calls++;
            setEnabled(false);
            try {
                dispatcher.onBackPressed();
            } catch (Throwable ex) {
                caught.add(ex);
            } finally {
                setEnabled(true);
            }
        }
    }

    private static OnBackPressedCallback callback(Runnable action) {
        return new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { action.run(); }
        };
    }

    private static Gate install(OnBackPressedDispatcher dispatcher, Owner owner) {
        Gate gate = new Gate(dispatcher);
        dispatcher.addCallback(owner, gate);
        return gate;
    }

    @Test public void newestGateForwardsToNewestEnabledDelegateOnce() {
        List<String> calls = new ArrayList<>();
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(() -> calls.add("fallback"));
        dispatcher.addCallback(callback(() -> calls.add("old")));
        dispatcher.addCallback(callback(() -> calls.add("new")));
        OnBackPressedCallback disabled = callback(() -> fail("disabled"));
        disabled.setEnabled(false);
        dispatcher.addCallback(disabled);
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        dispatcher.onBackPressed();
        assertEquals(Arrays.asList("new"), calls);
        assertEquals(1, gate.calls);
        assertTrue(gate.isEnabled());
    }

    @Test public void newestGateForwardsToNoCallbackFallbackOnce() {
        int[] calls = {0};
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(() -> calls[0]++);
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        dispatcher.onBackPressed();
        assertEquals(1, calls[0]);
        assertEquals(1, gate.calls);
        assertTrue(gate.isEnabled());
    }

    @Test public void newestGateCatchesThrowableFromDelegateAndRestoresItself() {
        AssertionError failure = new AssertionError("delegate");
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher();
        dispatcher.addCallback(callback(() -> { throw failure; }));
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        dispatcher.onBackPressed(); dispatcher.onBackPressed();
        assertEquals(Arrays.asList(failure, failure), gate.caught);
        assertTrue(gate.isEnabled());
    }

    @Test public void newestGateCatchesThrowingFallback() {
        NullPointerException failure = new NullPointerException("framework fallback");
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(() -> { throw failure; });
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        dispatcher.onBackPressed();
        assertEquals(Arrays.asList(failure), gate.caught);
    }

    @Test public void laterCallbackBypassesGateAndItsExceptionEscapes() {
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher();
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        IllegalStateException failure = new IllegalStateException("later fragment");
        dispatcher.addCallback(callback(() -> { throw failure; }));
        assertSame(failure, assertThrows(IllegalStateException.class, dispatcher::onBackPressed));
        assertEquals(0, gate.calls);
        assertTrue(gate.caught.isEmpty());
    }

    @Test public void fragmentStyleRemoveAndReaddOutranksPreviouslyLastGate() {
        List<String> calls = new ArrayList<>();
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher();
        OnBackPressedCallback fragment = callback(() -> calls.add("fragment"));
        dispatcher.addCallback(fragment);
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        fragment.remove(); dispatcher.addCallback(fragment);
        dispatcher.onBackPressed();
        assertEquals(Arrays.asList("fragment"), calls);
        assertEquals(0, gate.calls);
    }

    @Test public void lifecycleStopDisablesProtectionAndStartRestoresIt() {
        NullPointerException failure = new NullPointerException("fallback");
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(() -> { throw failure; });
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        dispatcher.onBackPressed();
        owner.stop();
        assertFalse(dispatcher.hasEnabledCallbacks());
        assertSame(failure, assertThrows(NullPointerException.class, dispatcher::onBackPressed));
        owner.start(); dispatcher.onBackPressed();
        assertEquals(Arrays.asList(failure, failure), gate.caught);
    }

    @Test public void lifecycleDestroyRemovesGatePermanently() {
        int[] fallback = {0};
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(() -> fallback[0]++);
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        owner.destroy();
        assertFalse(dispatcher.hasEnabledCallbacks());
        dispatcher.onBackPressed();
        assertEquals(1, fallback[0]);
        assertEquals(0, gate.calls);
        assertEquals(0, owner.lifecycle.getObserverCount());
    }

    @Test public void alreadyDestroyedOwnerNeverRegistersGate() {
        Owner owner = new Owner(); owner.start(); owner.destroy();
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher();
        install(dispatcher, owner);
        assertFalse(dispatcher.hasEnabledCallbacks());
        assertEquals(0, owner.lifecycle.getObserverCount());
    }

    @Test public void nestedDelegateRedispatchDoesNotReenterDisabledGate() {
        List<String> calls = new ArrayList<>();
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(() -> calls.add("fallback"));
        OnBackPressedCallback delegate = new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                calls.add("delegate");
                setEnabled(false);
                try { dispatcher.onBackPressed(); } finally { setEnabled(true); }
            }
        };
        dispatcher.addCallback(delegate);
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        dispatcher.onBackPressed();
        assertEquals(Arrays.asList("delegate", "fallback"), calls);
        assertEquals(1, gate.calls);
        assertTrue(gate.caught.isEmpty());
    }

    @Test public void destroyingOwnerDuringDelegateDoesNotResurrectGateInFinally() {
        int[] fallback = {0};
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(() -> fallback[0]++);
        Owner owner = new Owner(); owner.start();
        OnBackPressedCallback delegate = callback(owner::destroy);
        dispatcher.addCallback(delegate);
        Gate gate = install(dispatcher, owner);
        dispatcher.onBackPressed();
        delegate.remove();
        assertEquals(0, owner.lifecycle.getObserverCount());
        assertFalse(dispatcher.hasEnabledCallbacks());
        dispatcher.onBackPressed();
        assertEquals(1, fallback[0]);
        assertEquals(1, gate.calls);
    }

    @Test public void stoppingOwnerDuringDelegateDoesNotReactivateGateInFinally() {
        int[] fallback = {0};
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(() -> fallback[0]++);
        Owner owner = new Owner(); owner.start();
        OnBackPressedCallback delegate = callback(owner::stop);
        dispatcher.addCallback(delegate);
        Gate gate = install(dispatcher, owner);
        dispatcher.onBackPressed();
        delegate.remove();
        assertFalse(dispatcher.hasEnabledCallbacks());
        dispatcher.onBackPressed();
        assertEquals(1, fallback[0]);
        assertEquals(1, gate.calls);
    }

    @Test public void reentrantFallbackFailureIsCaughtWithoutReenteringGate() {
        AssertionError failure = new AssertionError("nested fallback");
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(() -> { throw failure; });
        OnBackPressedCallback delegate = new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                setEnabled(false);
                try { dispatcher.onBackPressed(); } finally { setEnabled(true); }
            }
        };
        dispatcher.addCallback(delegate);
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        dispatcher.onBackPressed();
        assertEquals(Arrays.asList(failure), gate.caught);
        assertEquals(1, gate.calls);
        assertTrue(delegate.isEnabled());
        assertTrue(gate.isEnabled());
    }

    private static final class PredictiveCallback extends OnBackPressedCallback {
        final List<String> events = new ArrayList<>();
        PredictiveCallback() { super(true); }
        @Override public void handleOnBackStarted(BackEventCompat event) { events.add("start"); }
        @Override public void handleOnBackProgressed(BackEventCompat event) { events.add("progress"); }
        @Override public void handleOnBackCancelled() { events.add("cancel"); }
        @Override public void handleOnBackPressed() { events.add("complete"); }
    }

    private static BackEventCompat event(float progress) {
        return new BackEventCompat(0, 0, progress, BackEventCompat.EDGE_LEFT);
    }

    @Test public void predictiveSameInputGateLosesWholeGestureIncludingNestedCompletion() {
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher();
        PredictiveCallback delegate = new PredictiveCallback();
        dispatcher.addCallback(delegate);
        dispatcher.dispatchOnBackStarted(event(0));
        dispatcher.dispatchOnBackProgressed(event(0.5f));
        dispatcher.onBackPressed();
        assertEquals(Arrays.asList("start", "progress", "complete"), delegate.events);
        delegate.events.clear();
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        dispatcher.dispatchOnBackStarted(event(0));
        dispatcher.dispatchOnBackProgressed(event(0.5f));
        dispatcher.onBackPressed();
        // The test API uses the same input for start and completion. During the
        // nested redispatch its predictive flag is still true, but the processor
        // has already cleared the active input, so nested completion is ignored.
        // Platform input is a separate source; this is not a device-input test.
        assertTrue(delegate.events.isEmpty());
        assertEquals(1, gate.calls);
        assertTrue(gate.caught.isEmpty());
        dispatcher.onBackPressed();
        assertEquals(Arrays.asList("complete"), delegate.events);
    }

    @Test public void predictiveCancellationNeverReachesDelegateBehindGate() {
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher();
        PredictiveCallback delegate = new PredictiveCallback();
        dispatcher.addCallback(delegate);
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        dispatcher.dispatchOnBackStarted(event(0));
        dispatcher.dispatchOnBackProgressed(event(0.5f));
        dispatcher.dispatchOnBackCancelled();
        assertTrue(delegate.events.isEmpty());
        assertEquals(0, gate.calls);
    }

    @Test public void predictiveGesturePinsDelegateEvenIfGateIsAddedLater() {
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher();
        PredictiveCallback delegate = new PredictiveCallback();
        dispatcher.addCallback(delegate);
        dispatcher.dispatchOnBackStarted(event(0));
        Owner owner = new Owner(); owner.start();
        Gate gate = install(dispatcher, owner);
        dispatcher.onBackPressed();
        assertEquals(Arrays.asList("start", "complete"), delegate.events);
        assertEquals(0, gate.calls);
    }

    @Test public void productionRetainsOuterThrowableBoundaryAndDynamicFragmentOrdering() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("app/src/main/java/in/anoopbhatia/mail/ActivityBase.java")))
            root = root.getParent();
        assertNotNull("Cannot locate repository from test working directory", root);
        String activity = Files.readString(root.resolve("app/src/main/java/in/anoopbhatia/mail/ActivityBase.java"));
        int start = activity.indexOf("public void onBackPressed() {");
        assertTrue("Existing outer back boundary must remain until a safe replacement is available", start >= 0);
        int end = activity.indexOf("public void onBackPressedFragment()", start);
        assertTrue("Cannot locate end of back boundary", end > start);
        String boundary = activity.substring(start, end);
        assertTrue(boundary.matches("(?s).*try\\s*\\{\\s*getOnBackPressedDispatcher\\(\\)\\.onBackPressed\\(\\);.*"));
        assertTrue(boundary.contains("catch (Throwable ex)"));
        assertTrue(boundary.contains("Log.w(ex)"));
        String fragment = Files.readString(root.resolve("app/src/main/java/in/anoopbhatia/mail/FragmentBase.java"));
        assertTrue(fragment.contains("backPressedCallback.remove();"));
        assertTrue(fragment.contains("activity.getOnBackPressedDispatcher().addCallback(backPressedCallback);"));
    }
}
