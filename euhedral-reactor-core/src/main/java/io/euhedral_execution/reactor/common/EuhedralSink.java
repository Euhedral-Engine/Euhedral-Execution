package io.euhedral_execution.reactor.common;

import io.euhedral_execution.core.flow_control.LatticeHotReceiver;
import io.euhedral_execution.core.frames.CallbackFrame;
import io.euhedral_execution.data_structures.queues.MpmcQueue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

@SuppressWarnings("unused")
public class EuhedralSink<T, R> extends LatticeHotReceiver<CallbackFrame<T, R>> {

    private final Sinks.Many<R> delegate;

    public EuhedralSink(MpmcQueue<R> buffer) {
        this.delegate = Sinks.many().unicast().onBackpressureBuffer(buffer);
    }

    public static Response toResponse(EmitResult result) {
        return switch (result) {
            case OK -> Response.OK;
            case FAIL_NON_SERIALIZED, FAIL_OVERFLOW -> Response.RETRY;
            case FAIL_ZERO_SUBSCRIBER, FAIL_CANCELLED, FAIL_TERMINATED -> Response.TERMINATE;
        };
    }

    @Override
    protected Response hookOnPush(CallbackFrame<T, R> frame) {
        if (!frame.isAlive()) {
            return Response.CANCEL;
        }
        EmitResult result = this.delegate.tryEmitNext(frame.getRetVal());

        return toResponse(result);
    }

    public EmitResult tryEmitNext(R obj) {
        return this.delegate.tryEmitNext(obj);
    }

    public EmitResult tryEmitComplete() {
        return this.delegate.tryEmitComplete();
    }

    public EmitResult tryEmitError(Throwable err) {
        return this.delegate.tryEmitError(err);
    }

    public Flux<R> asFlux() {
        return this.delegate.asFlux();
    }
}
