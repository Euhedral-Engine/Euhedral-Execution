package euhedral.io;

import euhedral.io.control_plane.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.utils.KeyHasher;
import euhedral.io.utils.PinnedThreadExecutor;
import java.util.concurrent.ThreadLocalRandom;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

public abstract class AbstractExecutor implements CloneableObject {

    protected final PinnedThreadExecutor executorService;
    private final Sinks.Many<Failure> errorReturn = Sinks.many().unicast()
            .onBackpressureBuffer(new MpscUnboundedXaddArrayQueue<>(1024));

    public AbstractExecutor(@NonNull PinnedThreadExecutor executorService) {
        this.executorService = executorService;
    }

    public final void reportErrorsTo(CloneableObject clone) {
        clone.errorChannel(errorReturn.asFlux());
    }

    @Override
    public void ingest(Publisher<AbstractFrame> flux) {
        final long password = KeyHasher.combine(ThreadLocalRandom.current().nextLong(),
                ThreadLocalRandom.current().nextLong());

        flux.subscribe(new Subscriber<>() {
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
        });
    }

    protected final void execute(AbstractFrame frame, long password) {
        frame.setNotifyCompletePassword(password);
        long startNs = 0;

        try {
            if(!frame.isAlive()) {
                frame.throwMeAsError();
            }
            startNs = System.nanoTime();
            execute(frame);
        } catch (Exception e) {
            long duration = System.nanoTime() - startNs;

            frame.setCancelledExecution(true);
            Failure failure = new Failure(duration, frame, e);
            EmitResult result;
            while (!(result = errorReturn.tryEmitNext(failure)).isSuccess()) {
                if (result == EmitResult.FAIL_CANCELLED || result == EmitResult.FAIL_TERMINATED
                        || result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
                    frame.kill();
                    throw new IllegalStateException(
                            "CRITICAL: No upstream connection to signal cancellation.");
                }
                Thread.onSpinWait();
            }
        }

        frame.notifyComplete(startNs, password);
    }

    public abstract void execute(AbstractFrame frame);

    @Override
    public abstract AbstractExecutor clone(CloneConfig cloneConfig);

    @Override
    public abstract AbstractExecutor clone(CloneConfig cloneConfig, PinnedThreadExecutor executor);

    @Override
    public void close() {
        errorReturn.tryEmitComplete();
    }
}
