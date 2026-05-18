package euhedral.io;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hashing.HasherApi;
import euhedral.io.config.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.interfaces.PipelineExecutor;
import euhedral.queues.PartitionedUnboundedMpscArrayQueue;
import java.util.concurrent.ThreadLocalRandom;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

public abstract class AbstractExecutor implements PipelineExecutor {

    protected final PinnedThreadExecutor executorService;
    private final Sinks.Many<Failure> errorReturn = Sinks.many().unicast()
            .onBackpressureBuffer(new PartitionedUnboundedMpscArrayQueue<>(1024));

    public AbstractExecutor(PinnedThreadExecutor executorService) {
        this.executorService = executorService;
    }

    @Override
    public final void reportErrorsTo(CloneableObject clone) {
        clone.errorChannel(this.errorReturn.asFlux());
    }

    @Override
    public void ingest(Publisher<? extends AbstractFrame> flux) {
        final long password = HasherApi.combine(ThreadLocalRandom.current().nextLong(),
                ThreadLocalRandom.current().nextLong());

        flux.subscribe(new ExecutionSubscriber(password));
    }

    protected final void execute(AbstractFrame frame, long password) {
        frame.setNotifyCompletePassword(password);

        try {
            if(!frame.isAlive()) {
                frame.throwMeAsError();
            }
            execute(frame);
        } catch (Exception e) {
            frame.setCancelledExecution(true);
            Failure failure = new Failure(frame, e);
            EmitResult result;
            while (!(result = this.errorReturn.tryEmitNext(failure)).isSuccess()) {
                if (result == EmitResult.FAIL_CANCELLED || result == EmitResult.FAIL_TERMINATED
                        || result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
                    frame.kill();
                    throw new IllegalStateException(
                            "CRITICAL: No upstream connection to signal cancellation.");
                }
                Thread.onSpinWait();
            }
        }

        frame.notifyComplete(password);
    }

    @Override
    public abstract AbstractExecutor clone(CloneConfig cloneConfig);

    @Override
    public abstract AbstractExecutor clone(CloneConfig cloneConfig, PinnedThreadExecutor executor);

    @Override
    public void close() {
        this.errorReturn.tryEmitComplete();
    }

    protected class ExecutionSubscriber implements Subscriber<AbstractFrame> {
        protected final long password;

        public ExecutionSubscriber(long password) {
            this.password = password;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AbstractFrame frame) {
            if(frame.isUseVThread()) {
                executorService.vThread(() -> execute(frame, password));
            } else {
                execute(frame, password);
            }
        }

        @Override
        public void onError(Throwable throwable) {

        }

        @Override
        public void onComplete() {

        }
    }
}
