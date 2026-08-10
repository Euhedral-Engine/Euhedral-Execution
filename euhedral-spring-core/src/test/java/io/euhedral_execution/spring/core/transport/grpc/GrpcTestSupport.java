package io.euhedral_execution.spring.core.transport.grpc;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.ArrayList;
import java.util.List;

final class GrpcTestSupport {

    private GrpcTestSupport() {}

    static final class RecordingReceiver implements LatticeReceiver {

        final List<AbstractFrame> frames = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        int completions;

        @Override
        public void push(AbstractFrame frame) {
            this.frames.add(frame);
        }

        @Override
        public void onComplete() {
            this.completions++;
        }

        @Override
        public void onError(Throwable error) {
            this.errors.add(error);
        }

        @Override
        public void addUpstream(LatticeSource upstream) {}
    }

    static final class RecordingClientObserver extends ClientCallStreamObserver<GrpcMessage> {

        final List<GrpcMessage> messages = new ArrayList<>();
        final List<Integer> requests = new ArrayList<>();
        boolean autoRequestDisabled;
        boolean ready;
        int completions;
        Runnable onReady;
        Throwable error;
        String cancelMessage;
        Throwable cancelCause;

        @Override
        public void cancel(String message, Throwable cause) {
            this.cancelMessage = message;
            this.cancelCause = cause;
        }

        @Override
        public void disableAutoRequestWithInitial(int request) {
            this.autoRequestDisabled = true;
            if (request > 0) {
                this.requests.add(request);
            }
        }

        @Override
        public boolean isReady() {
            return this.ready;
        }

        void setReady(boolean ready) {
            this.ready = ready;
            if (ready && this.onReady != null) {
                this.onReady.run();
            }
        }

        @Override
        public void setOnReadyHandler(Runnable runnable) {
            this.onReady = runnable;
        }

        @Override
        public void disableAutoInboundFlowControl() {
            this.autoRequestDisabled = true;
        }

        @Override
        public void request(int demand) {
            this.requests.add(demand);
        }

        @Override
        public void setMessageCompression(boolean enabled) {}

        @Override
        public void onNext(GrpcMessage message) {
            this.messages.add(message);
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
            this.completions++;
        }
    }

    static final class RecordingServerObserver extends ServerCallStreamObserver<GrpcMessage> {

        final List<GrpcMessage> messages = new ArrayList<>();
        final List<Integer> requests = new ArrayList<>();
        boolean autoRequestDisabled;
        boolean cancelled;
        boolean ready;
        int completions;
        Runnable onCancel;
        Runnable onClose;
        Runnable onReady;
        Throwable error;

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void setOnCancelHandler(Runnable runnable) {
            this.onCancel = runnable;
        }

        @Override
        public void setCompression(String compression) {}

        @Override
        public void disableAutoRequest() {
            this.autoRequestDisabled = true;
        }

        @Override
        public boolean isReady() {
            return this.ready;
        }

        void setReady(boolean ready) {
            this.ready = ready;
            if (ready && this.onReady != null) {
                this.onReady.run();
            }
        }

        @Override
        public void setOnReadyHandler(Runnable runnable) {
            this.onReady = runnable;
        }

        @Override
        public void disableAutoInboundFlowControl() {
            this.autoRequestDisabled = true;
        }

        @Override
        public void request(int demand) {
            this.requests.add(demand);
        }

        @Override
        public void setMessageCompression(boolean enabled) {}

        @Override
        public void setOnCloseHandler(Runnable runnable) {
            this.onClose = runnable;
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
        public void onCompleted() {
            this.completions++;
        }

        void cancel() {
            this.cancelled = true;
            this.onCancel.run();
        }

        void close() {
            this.onClose.run();
        }
    }
}
