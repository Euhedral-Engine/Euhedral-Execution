package euhedral.io.test_utils;

import euhedral.atomics.PaddedLongAdder;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.impl.FrameManager;
import java.util.concurrent.CountDownLatch;

import lombok.Getter;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class TestPublisher implements Publisher<AbstractFrame>, Subscription {

    @Getter
    private final TestFrame[] myFrames;

    private PaddedLongAdder counters;
    private CountDownLatch trigger;
    private Subscriber<? super AbstractFrame> subscriber;
    private int internalIter = 0;

    @Getter
    private volatile boolean complete = false;

    public TestPublisher(TestFrame[] frames) {
        this.myFrames = frames;
    }

    public void reset(CountDownLatch trigger, PaddedLongAdder counters) {
        this.counters = counters;
        this.trigger = trigger;
        this.internalIter = 0;
        this.complete = false;
    }

    @Override
    public void request(long demand) {
        if (demand <= 0 || internalIter >= myFrames.length || complete) {
            return;
        }

        FrameManager<Void, TestFrame> recycler = null;
        long total = 0;
        for (int i = 0; i < demand && internalIter < myFrames.length; i++) {
            TestFrame f = myFrames[internalIter++];
            f.trigger = trigger;
            f.counters = counters;
            subscriber.onNext(f);
            total++;
            recycler = f.getRecycler();
        }

//        countDown.addAndGet(-recycler.dump(total, TestFrame.PASSWORD));

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
