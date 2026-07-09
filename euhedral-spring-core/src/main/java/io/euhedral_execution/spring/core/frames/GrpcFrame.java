package io.euhedral_execution.spring.core.frames;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.euhedral_execution.spring.core.utils.KillSwitch;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import lombok.Setter;

@SuppressWarnings("unused")
public class GrpcFrame extends AbstractFrame {

    @Getter
    private final ServerCallStreamObserver<GrpcMessage> client;
    @Getter
    private final CommunicationMethod method;
    private final KillSwitch killSwitch;
    @Getter
    @Setter
    private GrpcMessage grpcMessage;

    public GrpcFrame(long idHash, GrpcMessage grpcMessage,
            CommunicationMethod method,
            ServerCallStreamObserver<GrpcMessage> client, FrameManager<GrpcMessage, GrpcFrame> recycleSink,
            KillSwitch ks) {
        super(idHash, recycleSink);

        this.grpcMessage = grpcMessage;
        this.method = method;
        this.client = client;
        this.killSwitch = ks;
    }

    @Override
    public void execute() {

    }

    @Override
    public boolean isAlive() {
        return killSwitch == null || !killSwitch.isBooped();
    }

    @Override
    public void kill() {
        if (killSwitch != null) {
            killSwitch.boop();
        }
    }

    public void respond(GrpcMessage response) {
        if (this.client != null) {
            Objects.requireNonNull(response);

            long cycles = 0;
            while(!this.client.isReady()) {
                if((cycles++ & 127) == 0) {
                    Thread.onSpinWait();
                } else if (cycles < 512) {
                    Thread.yield();
                } else {
                    LockSupport.parkNanos(10_000L);
                }
            }
            this.client.onNext(response);
        }
    }

    public void replace(GrpcMessage message) {
        reset();
        this.grpcMessage = message;
    }

    public enum CommunicationMethod {
        SINGLE_RESPONSE,
        CLIENT_STREAM,
        SERVER_STREAM,
        BIDI
    }
}
