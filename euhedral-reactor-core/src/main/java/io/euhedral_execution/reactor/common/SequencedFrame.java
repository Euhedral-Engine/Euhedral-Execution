package io.euhedral_execution.reactor.common;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.impl.FrameManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.Getter;
import lombok.Setter;

public class SequencedFrame<T, R> extends AbstractFrame {

    private final FrameSequencer<T, R> sequencer;

    @Getter
    private final Function<T, R> function;

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

    public SequencedFrame(
            long idHash,
            T payload,
            Function<T, R> function,
            AtomicBoolean killSwitch,
            FrameSequencer<T, R> sequencer,
            FrameManager<T, SequencedFrame<T, R>> recycler) {
        super(idHash, recycler, killSwitch);
        this.sequencer = sequencer;
        this.function = function;
        this.payload = payload;
    }

    @Override
    public void execute() {
        retVal = function.apply(payload);
    }

    public void replace(T payload) {
        this.payload = payload;
        this.retVal = null;
        this.ready = false;
    }

    @Override
    public void doFinally() {
        this.ready = true;
        sequencer.drain(sequencerPassword);
    }
}
