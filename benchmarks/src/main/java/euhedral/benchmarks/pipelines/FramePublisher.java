package euhedral.benchmarks.pipelines;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.ScaffoldingSource;
import euhedral.io.interfaces.ScaffoldingTerminal;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ThreadLocalRandom;

import lombok.Getter;

public class FramePublisher implements ScaffoldingSource {
    private static final VarHandle COMPLETE;

    static {
        try {
            COMPLETE = MethodHandles.lookup().findVarHandle(FramePublisher.class, "complete", boolean.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Getter
    private final AbstractFrame[] frames;
    private final int start;
    private final int end;

    private long seed = ThreadLocalRandom.current().nextLong();

    private ScaffoldingTerminal terminal;
    private int internalIter = 0;

    private boolean complete = false;

    public FramePublisher(AbstractFrame[] frames, int start, int end) {
        this.frames = frames;
        this.start = start;
        this.end = end;
        this.internalIter = start;
    }

    public void reset() {
        this.internalIter = this.start;
        COMPLETE.setRelease(this, false);
    }

    @Override
    public void request(long demand) {
        if (demand <= 0 || internalIter >= this.end || (boolean) COMPLETE.getOpaque(this)) {
            return;
        }

        for (int i = 0; i < demand && internalIter < this.end; i++) {
            AbstractFrame f = this.frames[this.internalIter++];
            f.randomizeHash(++this.seed);
            terminal.onNext(f);
        }

        if (this.internalIter >= this.end) {
            this.terminal.onComplete();
            COMPLETE.setRelease(this, true);
            this.terminal = null;
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
