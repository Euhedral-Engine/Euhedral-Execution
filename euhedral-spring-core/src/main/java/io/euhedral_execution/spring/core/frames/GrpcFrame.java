package io.euhedral_execution.spring.core.frames;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.Setter;

@SuppressWarnings("unused")
public class GrpcFrame extends AbstractFrame {

    @Getter
    private final CommunicationMethod method;
    private final Consumer<GrpcMessage> responseCallback;
    private final Runnable completeCallback;
    private final Consumer<Throwable> errorCallback;

    @Getter
    @Setter
    private GrpcMessage grpcMessage;

    public GrpcFrame(long idHash, GrpcMessage grpcMessage,
            CommunicationMethod method,
            Consumer<GrpcMessage> responseCallback,
            Runnable completeCallback,
            Consumer<Throwable> errorCallback,
            FrameManager<GrpcMessage, GrpcFrame> recycleSink,
            AtomicBoolean killSwitch) {
        super(idHash, recycleSink, killSwitch);

        this.grpcMessage = grpcMessage;
        this.method = method;
        this.responseCallback = responseCallback;
        this.completeCallback = completeCallback;
        this.errorCallback = errorCallback;
    }

    public void respond(GrpcMessage response) {
        this.responseCallback.accept(response);
        if (this.method == CommunicationMethod.CLIENT_STREAM
                || this.method == CommunicationMethod.SINGLE_RESPONSE) {
            complete();
        }
    }

    public void complete() {
        this.completeCallback.run();
    }

    public void sendError(StatusRuntimeException status) {
        this.errorCallback.accept(status);
    }

    @Override
    public void doFinallyWithError(Throwable t) {
        sendError(new StatusRuntimeException(Status.fromThrowable(t)));
    }

    public void replace(GrpcMessage message) {
        resetHash();
        this.grpcMessage = message;
    }

    public enum CommunicationMethod {
        SINGLE_RESPONSE,
        CLIENT_STREAM,
        SERVER_STREAM,
        BIDI
    }
}
