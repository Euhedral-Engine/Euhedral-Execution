package io.euhedral_execution.spring.core.transport.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.spring.core.transport.grpc.GrpcTestSupport.RecordingClientObserver;
import io.euhedral_execution.spring.core.transport.grpc.ReactorGrpcClientHandler.GrpcSubscriber;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

class ReactorGrpcClientHandlerTest {

    private static GrpcMessage message(int value) {
        return GrpcUtils.toGrpc(null, new byte[] {(byte) value}, true);
    }

    @Test
    void responseDemandAndTerminalSignalsAreForwarded() {
        RecordingClientObserver transport = new RecordingClientObserver();
        ReactorGrpcClientHandler handler = new ReactorGrpcClientHandler();
        RecordingSubscriber downstream = new RecordingSubscriber();
        handler.beforeStart(transport);
        handler.subscribe(downstream);

        downstream.subscription.request(0);
        downstream.subscription.request(-1);
        downstream.subscription.request(2);
        GrpcMessage first = message(1);
        GrpcMessage second = message(2);
        handler.onNext(first);
        handler.onNext(second);
        handler.onCompleted();
        handler.onCompleted();

        assertEquals(List.of(2), transport.requests);
        assertEquals(List.of(first, second), downstream.messages);
        assertEquals(1, downstream.completions);
        assertFalse(handler.isOpen());
    }

    @Test
    void duplicateAndLateSubscribersReceiveDeterministicTerminalSignals() {
        ReactorGrpcClientHandler handler = new ReactorGrpcClientHandler();
        RecordingSubscriber first = new RecordingSubscriber();
        RecordingSubscriber duplicate = new RecordingSubscriber();
        handler.subscribe(first);
        handler.subscribe(duplicate);

        assertInstanceOf(IllegalAccessException.class, duplicate.error);

        handler.onCompleted();
        RecordingSubscriber late = new RecordingSubscriber();
        handler.subscribe(late);

        assertEquals(1, first.completions);
        assertEquals(1, late.completions);
    }

    @Test
    void requestSubscriberDrainsInOrderBeforeCompletingTransport() {
        RecordingClientObserver transport = new RecordingClientObserver();
        ReactorGrpcClientHandler handler = new ReactorGrpcClientHandler(4);
        handler.beforeStart(transport);
        GrpcSubscriber subscriber = handler.getSubscriber();
        RecordingSubscription publisher = new RecordingSubscription();
        subscriber.onSubscribe(publisher);
        GrpcMessage first = message(1);
        GrpcMessage second = message(2);

        subscriber.onNext(first);
        subscriber.onNext(second);
        subscriber.onComplete();

        assertTrue(transport.messages.isEmpty());
        assertEquals(0, transport.completions);

        transport.setReady(true);

        assertEquals(List.of(first, second), transport.messages);
        assertEquals(1, transport.completions);
    }

    @Test
    void requestSubscriberCompletesWhenReadyQueueIsAlreadyDrained() {
        RecordingClientObserver transport = new RecordingClientObserver();
        transport.ready = true;
        ReactorGrpcClientHandler handler = new ReactorGrpcClientHandler(4);
        handler.beforeStart(transport);
        GrpcSubscriber subscriber = handler.getSubscriber();
        subscriber.onSubscribe(new RecordingSubscription());

        subscriber.onNext(message(1));
        subscriber.onComplete();
        subscriber.onComplete();

        assertEquals(1, transport.messages.size());
        assertEquals(1, transport.completions);
    }

    @Test
    void requestSubscriberPropagatesOnlyTheFirstError() {
        RecordingClientObserver transport = new RecordingClientObserver();
        ReactorGrpcClientHandler handler = new ReactorGrpcClientHandler(4);
        handler.beforeStart(transport);
        GrpcSubscriber subscriber = handler.getSubscriber();
        subscriber.onSubscribe(new RecordingSubscription());
        RuntimeException failure = new RuntimeException("failure");

        subscriber.onError(failure);
        subscriber.onError(new AssertionError("duplicate"));
        subscriber.onNext(message(1));

        assertSame(failure, transport.error);
        assertTrue(transport.messages.isEmpty());
    }

    private static final class RecordingSubscription implements Subscription {

        final List<Long> requests = new ArrayList<>();
        boolean cancelled;

        @Override
        public void request(long demand) {
            this.requests.add(demand);
        }

        @Override
        public void cancel() {
            this.cancelled = true;
        }
    }

    private static final class RecordingSubscriber implements CoreSubscriber<GrpcMessage> {

        final List<GrpcMessage> messages = new ArrayList<>();
        int completions;
        Throwable error;
        Subscription subscription;

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(GrpcMessage message) {
            this.messages.add(message);
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onComplete() {
            this.completions++;
        }

        @Override
        public Context currentContext() {
            return Context.empty();
        }
    }
}
