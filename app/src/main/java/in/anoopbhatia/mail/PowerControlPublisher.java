package in.anoopbhatia.mail;

import org.reactivestreams.FlowAdapters;

import java.util.concurrent.Flow;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.ReplayProcessor;

/** Reactive Streams/Flow boundary for Android device controls. */
final class PowerControlPublisher<T> {
    // Preserve the existing unbounded replay and synchronous delivery semantics.
    private final ReplayProcessor<T> updates = ReplayProcessor.create();

    static <T> Flow.Publisher<T> available(Iterable<T> controls) {
        return FlowAdapters.toFlowPublisher(Flowable.fromIterable(controls));
    }

    Flow.Publisher<T> publisher() {
        return FlowAdapters.toFlowPublisher(updates);
    }

    void submit(T control) {
        updates.onNext(control);
    }
}
