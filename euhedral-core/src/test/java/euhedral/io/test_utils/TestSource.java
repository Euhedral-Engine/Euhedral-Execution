package euhedral.io.test_utils;

import euhedral.atomics.PaddedLongAdder;
import euhedral.io.generics.ScaffoldingSource;
import euhedral.io.generics.ScaffoldingTerminal;
import java.util.concurrent.CountDownLatch;

import lombok.Getter;

public class TestSource implements ScaffoldingSource {

    @Getter
    private final TestFrame[] myFrames;

    private PaddedLongAdder counters;
    private CountDownLatch trigger;
    private ScaffoldingTerminal terminal;
    private int internalIter = 0;

    @Getter
    private volatile boolean complete = false;

    public TestSource(TestFrame[] frames) {
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

        for (int i = 0; i < demand && internalIter < myFrames.length; i++) {
            TestFrame f = myFrames[internalIter++];
            f.trigger = trigger;
            f.counters = counters;
            terminal.onNext(f);
        }

//        countDown.addAndGet(-recycler.dump(total, TestFrame.PASSWORD));

        if (internalIter >= myFrames.length) {
            terminal.onComplete();
            complete = true;
            terminal = null;
        }
    }

    @Override
    public void cancel() {
    }

    @Override
    public void addDownstream(ScaffoldingTerminal terminal) {
        this.terminal = terminal;
        this.terminal.addUpstream(this);
    }
}
