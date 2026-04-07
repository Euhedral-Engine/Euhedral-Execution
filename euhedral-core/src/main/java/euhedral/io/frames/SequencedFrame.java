package euhedral.io.frames;

import euhedral.io.utils.MpscFrameRecycler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.Getter;
import lombok.Setter;

public class SequencedFrame extends AbstractFrame {

    private final String id;

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

    public SequencedFrame(String id, long idHash, int sequenceNumber, long destinationHash,
            Object payload, Function<Object, Object> function, AtomicBoolean killSwitch, FrameSequencer<Object> sequencer,
            MpscFrameRecycler recycler) {
        super(idHash, destinationHash, recycler);
        this.id = id;
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
    public String getId() {
        return id;
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
        this.sequenceNumber = sequenceNumber;
        this.payload = payload;
        this.retVal = null;
        this.ready = false;
        setCancelledExecution(false);
    }

    @Override
    public void doFinally() {
        this.ready = true;
        sequencer.notifyComplete(sequencerPassword);
    }
}
