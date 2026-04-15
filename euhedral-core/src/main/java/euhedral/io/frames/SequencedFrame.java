package euhedral.io.frames;

import euhedral.io.impl.FrameSequencer;
import euhedral.io.impl.FrameManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.Getter;
import lombok.Setter;

public class SequencedFrame extends AbstractFrame {

    private final AtomicBoolean killSwitch;

    private final FrameSequencer<Object> sequencer;

    @Getter
    private final Function<Object, Object> function;

    @Getter
    private int sequenceNumber;

    @Getter
    @Setter
    private Object payload;

    @Getter
    private Object retVal;

    @Getter
    @Setter
    private long sequencerPassword;

    @Getter
    private boolean ready = false;

    public SequencedFrame(long idHash, int sequenceNumber,
            Object payload, Function<Object, Object> function, AtomicBoolean killSwitch, FrameSequencer<Object> sequencer,
            FrameManager<Object, SequencedFrame> recycler) {
        super(idHash, recycler);
        this.killSwitch = killSwitch;
        this.sequencer = sequencer;
        this.sequenceNumber = sequenceNumber;
        this.function = function;
        this.payload = payload;
    }

    public void apply() {
        retVal = function.apply(payload);
    }

    @Override
    public long getSizeBytes() {
        return 256;
    }

    @Override
    public boolean isAlive() {
        return killSwitch.get();
    }

    @Override
    public void kill() {
        killSwitch.set(true);
    }

    public void replace(int sequenceNumber, Object payload) {
        reset();
        this.sequenceNumber = sequenceNumber;
        this.payload = payload;
        this.retVal = null;
        this.ready = false;
    }

    @Override
    public void doFinally() {
        this.ready = true;
        sequencer.notifyComplete(sequencerPassword);
    }
}
