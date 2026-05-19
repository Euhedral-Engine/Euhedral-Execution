package euhedral.io.frames;

import euhedral.io.impl.FrameManager;
import euhedral.io.impl.FrameSequencer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.Getter;
import lombok.Setter;

public class SequencedFrame<T, R> extends AbstractFrame {

    private final AtomicBoolean killSwitch;

    private final FrameSequencer<T, R> sequencer;

    @Getter
    private final Function<T, R> function;

    @Getter
    private int sequenceNumber;

    @Getter
    @Setter
    private T payload;

    @Getter
    private R retVal;

    @Getter
    @Setter
    private long sequencerPassword;

    @Getter
    private boolean ready = false;

    public SequencedFrame(long idHash, int sequenceNumber,
            T payload, Function<T, R> function, AtomicBoolean killSwitch, FrameSequencer<T, R> sequencer,
            FrameManager<T, SequencedFrame<T, R>> recycler) {
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

    public void replace(int sequenceNumber, T payload) {
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
