package in.anoopbhatia.mail;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable, independent request-code registrations, including launches made after STARTED. */
final class ActivityResultRouter {
    interface Callback {
        void onActivityResult(int requestCode, ActivityResult result);
    }

    private static final String KEY = "fair:activity_result_requests";
    private final ActivityResultRegistry registry;
    private final LifecycleOwner owner;
    private final String keyPrefix;
    private final Callback callback;
    private final Map<Integer, Registration> registrations = new LinkedHashMap<>();

    ActivityResultRouter(ActivityResultRegistry registry, LifecycleOwner owner,
                         String keyPrefix, Callback callback) {
        this.registry = registry;
        this.owner = owner;
        this.keyPrefix = keyPrefix;
        this.callback = callback;
        owner.getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event == Lifecycle.Event.ON_DESTROY)
                close();
            else
                for (Registration registration : new ArrayList<>(registrations.values()))
                    synchronize(registration);
        });
    }

    void restore(Bundle state) {
        if (state == null)
            return;
        int[] requests = state.getIntArray(KEY);
        if (requests != null)
            for (int request : requests)
                register(request);
    }

    void save(Bundle state) {
        int[] requests = new int[registrations.size()];
        int i = 0;
        for (int request : registrations.keySet())
            requests[i++] = request;
        state.putIntArray(KEY, requests);
    }

    void launch(Intent intent, int requestCode) {
        register(requestCode).launcher.launch(intent);
    }

    void close() {
        for (Registration registration : new ArrayList<>(registrations.values())) {
            registration.lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
            registration.launcher.unregister();
        }
        registrations.clear();
    }

    private Registration register(final int requestCode) {
        Registration existing = registrations.get(requestCode);
        if (existing != null)
            return existing;
        if (owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED)
            throw new IllegalStateException("Cannot launch an activity for a destroyed owner");

        // The private lifecycle starts below STARTED, even when the real owner is resumed.
        // Registry registration is therefore legal for request codes first used on a click.
        Registration registration = new Registration();
        String key = keyPrefix + ":activity-result:" + requestCode;
        registration.launcher = registry.register(key, registration,
                new ActivityResultContracts.StartActivityForResult(),
                result -> callback.onActivityResult(requestCode, result));
        registrations.put(requestCode, registration);
        synchronize(registration);
        // Keep the registration until destruction: two outstanding launches with the same
        // request code must both be delivered, including when the first callback relaunches.
        // A failed launch cannot contaminate another request code's independent callback.
        return registration;
    }

    private void synchronize(Registration registration) {
        Lifecycle.State state = owner.getLifecycle().getCurrentState();
        registration.lifecycle.setCurrentState(state.isAtLeast(Lifecycle.State.STARTED)
                ? Lifecycle.State.STARTED : Lifecycle.State.CREATED);
    }

    private static final class Registration implements LifecycleOwner {
        final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
        ActivityResultLauncher<Intent> launcher;

        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return lifecycle;
        }
    }
}
