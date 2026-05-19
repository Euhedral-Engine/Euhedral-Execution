package euhedral.io;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.flow_control.LockFreeSink;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.CancelFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.interfaces.PipelineExecutor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractExecutor implements PipelineExecutor {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final PinnedThreadExecutor executorService;
    private LockFreeSink completeSink;

    public AbstractExecutor(PinnedThreadExecutor executorService) {
        this.executorService = executorService;
    }

    @Override
    public final void reportCompletionsTo(CloneableObject clone) {
        this.completeSink = clone.completeChannel();
    }

    @Override
    public void input(Publisher<? extends AbstractFrame> flux) {
        flux.subscribe(new ExecutionSubscriber());
    }

    private void executeInternal(AbstractFrame frame) {
        try {
            if (!frame.isAlive()) {
                frame.throwMeAsError();
            }
            execute(frame);
        } catch (Exception e) {
            frame.setCancelledExecution(true);
            if(!(e instanceof CancelFrame)) {
                logger.error("Uncaught exception while executing frame. {}", frame, e);
            }
        }

        while (!this.completeSink.offer(frame)) {
            Thread.onSpinWait();
        }
    }

    @Override
    public abstract AbstractExecutor clone(CloneConfig cloneConfig);

    @Override
    public abstract AbstractExecutor clone(CloneConfig cloneConfig, PinnedThreadExecutor executor);

    @Override
    public void close() {
    }

    protected class ExecutionSubscriber implements Subscriber<AbstractFrame> {

        public ExecutionSubscriber() {

        }

        @Override
        public void onSubscribe(Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AbstractFrame frame) {
            if (frame.isUseVThread()) {
                executorService.vThread(() -> executeInternal(frame));
            } else {
                executeInternal(frame);
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
