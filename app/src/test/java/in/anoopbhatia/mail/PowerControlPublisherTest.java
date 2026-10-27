package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PowerControlPublisherTest {
    @Test
    public void discoveryHonorsDemandAndCompletes() {
        RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
        PowerControlPublisher.available(List.of("sync")).subscribe(subscriber);
        assertTrue(subscriber.values.isEmpty());
        subscriber.subscription.request(1);
        assertEquals(List.of("sync"), subscriber.values);
        assertTrue(subscriber.complete);
        assertNull(subscriber.error);
    }

    @Test
    public void emptyDiscoveryCompletesWithoutValues() {
        RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
        PowerControlPublisher.<String>available(List.of()).subscribe(subscriber);
        subscriber.subscription.request(1);
        assertTrue(subscriber.values.isEmpty());
        assertTrue(subscriber.complete);
        assertNull(subscriber.error);
    }

    @Test
    public void initialStateAndActionsReplayToLateSubscribersInOrder() {
        PowerControlPublisher<Boolean> publisher = new PowerControlPublisher<>();
        publisher.submit(true); // Service publishes current preference before subscribing.
        RecordingSubscriber<Boolean> first = new RecordingSubscriber<>();
        publisher.publisher().subscribe(first);
        first.subscription.request(Long.MAX_VALUE);
        publisher.submit(false);
        publisher.submit(true);
        RecordingSubscriber<Boolean> late = new RecordingSubscriber<>();
        publisher.publisher().subscribe(late);
        late.subscription.request(Long.MAX_VALUE);
        assertEquals(List.of(true, false, true), first.values);
        assertEquals(first.values, late.values);
        assertFalse(first.complete);
        assertNull(first.error);
        assertNull(late.error);
    }

    @Test
    public void updatesWaitForDemandAndStopAfterCancellation() {
        PowerControlPublisher<Boolean> publisher = new PowerControlPublisher<>();
        RecordingSubscriber<Boolean> subscriber = new RecordingSubscriber<>();
        publisher.publisher().subscribe(subscriber);
        publisher.submit(true);
        publisher.submit(false);
        assertTrue(subscriber.values.isEmpty());
        subscriber.subscription.request(1);
        assertEquals(List.of(true), subscriber.values);
        subscriber.subscription.request(1);
        assertEquals(List.of(true, false), subscriber.values);
        subscriber.subscription.cancel();
        publisher.submit(true);
        assertEquals(List.of(true, false), subscriber.values);
        assertNull(subscriber.error);
    }

    @Test
    public void separateServicePublisherRequestsDoNotShareHistory() {
        PowerControlPublisher<Boolean> oldSession = new PowerControlPublisher<>();
        oldSession.submit(true);
        PowerControlPublisher<Boolean> newSession = new PowerControlPublisher<>();
        RecordingSubscriber<Boolean> subscriber = new RecordingSubscriber<>();
        newSession.publisher().subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);
        assertTrue(subscriber.values.isEmpty());
        newSession.submit(false);
        assertEquals(List.of(false), subscriber.values);
        assertFalse(subscriber.complete);
        assertNull(subscriber.error);
    }

    private static final class RecordingSubscriber<T> implements Flow.Subscriber<T> {
        final List<T> values = new ArrayList<>();
        Flow.Subscription subscription;
        Throwable error;
        boolean complete;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(T value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onComplete() {
            complete = true;
        }
    }
}
