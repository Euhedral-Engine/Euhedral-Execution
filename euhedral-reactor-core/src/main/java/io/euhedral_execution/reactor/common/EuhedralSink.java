package io.euhedral_execution.reactor.common;

import io.euhedral_execution.core.flow_control.LatticeHotReceiver;
import io.euhedral_execution.core.frames.CallbackFrame;
import io.euhedral_execution.core.frames.ChainFrame;
import io.euhedral_execution.data_structures.queues.MpmcQueue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

@SuppressWarnings("unused")
public class EuhedralSink<T, R> extends LatticeHotReceiver<ChainFrame<CallbackFrame<T, R>>> {

    public static Response toResponse(EmitResult result) {
        return switch (result) {
            case OK -> Response.OK;
            case FAIL_NON_SERIALIZED -> Response.RETRY;
            case FAIL_OVERFLOW -> Response.RETRY;
            case FAIL_ZERO_SUBSCRIBER -> Response.TERMINATE;
            case FAIL_CANCELLED -> Response.TERMINATE;
            case FAIL_TERMINATED -> Response.TERMINATE;
        };
    }

    private final Sinks.Many<R> delegate;

    public EuhedralSink(MpmcQueue<R> buffer) {
        this.delegate = Sinks.many().unicast().onBackpressureBuffer(buffer);
    }

    @Override
    protected Response hookOnPush(ChainFrame<CallbackFrame<T, R>> frame) {
        if (!frame.isAlive()) {
            return Response.CANCEL;
        }
        CallbackFrame<T, R> casted = (CallbackFrame<T, R>) frame;
        EmitResult result = this.delegate.tryEmitNext(casted.getRetVal());

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
