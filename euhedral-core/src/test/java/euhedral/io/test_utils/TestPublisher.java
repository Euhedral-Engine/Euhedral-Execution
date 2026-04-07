package euhedral.io.test_utils;

import euhedral.io.frames.AbstractFrame;
import java.util.concurrent.CountDownLatch;
import lombok.Getter;
import org.jctools.util.PaddedAtomicLong;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class TestPublisher implements Publisher<AbstractFrame>, Subscription {

    private final TestFrame[] myFrames;

    private PaddedAtomicLong countDown;
    private CountDownLatch trigger;
    private Subscriber<? super AbstractFrame> subscriber;
    private int internalIter = 0;

    @Getter
    private volatile boolean complete = false;

    public TestPublisher(TestFrame[] frames) {
        this.myFrames = frames;
    }

    public void reset(CountDownLatch trigger, PaddedAtomicLong countDown) {
        this.countDown = countDown;
        this.trigger = trigger;
        this.internalIter = 0;
        this.complete = false;
    }

    @Override
    public void request(long demand) {
        if (demand <= 0 || internalIter >= myFrames.length) {
            return;
        }

        for (int i = 0; i < demand && internalIter < myFrames.length; i++) {
            TestFrame f = myFrames[internalIter++];
            f.trigger = trigger;
            f.countDown = countDown;
            subscriber.onNext(f);
        }
        if (internalIter >= myFrames.length) {
            subscriber.onComplete();
            complete = true;
            subscriber = null;
        }
    }

    @Override
    public void cancel() {
    }

    @Override
    public void subscribe(Subscriber<? super AbstractFrame> subscriber) {
        this.subscriber = subscriber;
        subscriber.onSubscribe(this);
    }
}
