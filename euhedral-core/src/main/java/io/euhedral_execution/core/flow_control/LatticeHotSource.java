package io.euhedral_execution.core.flow_control;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.utils.CommonVarHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;
import java.util.function.Function;

/// Used for pushing frames from one stage to the next. Assumes that only one thread will control
/// the push side. This class can only have one downstream.
@SuppressWarnings("unused")
public final class LatticeHotSource implements LatticeSource, Consumer<AbstractFrame> {

    private static final VarHandle COMPLETE = CommonVarHandles.complete(LatticeHotSource.class);
    private static final VarHandle DOWNSTREAM = CommonVarHandles.downstream(LatticeHotSource.class);

    private final Consumer<AbstractFrame> applyToEach;

    boolean complete = false;
    LatticeReceiver downstream = null;

    public LatticeHotSource() {
        this.applyToEach = null;
    }

    public LatticeHotSource(
            Consumer<AbstractFrame> applyToEach) {
        this.applyToEach = applyToEach;
    }

    @Override
    public long pull(Consumer<AbstractFrame> consumer,
            Function<AbstractFrame, Boolean> stopCondition, long demand) {
        return 0;
    }

    @Override
    public void accept(AbstractFrame frame) {
        if (DOWNSTREAM.getOpaque(this) == null || (boolean) COMPLETE.getOpaque(this)) {
            return;
        }

        if (this.applyToEach != null) {
            this.applyToEach.accept(frame);
        }

        LatticeReceiver terminal = (LatticeReceiver) DOWNSTREAM.getOpaque(this);
        if (terminal != null) {
            terminal.push(frame);
        }
    }

    @Override
    public void addDownstream(LatticeReceiver terminal) {
        if (!COMPLETE.compareAndSet(this, true, false) && !DOWNSTREAM.compareAndSet(this, null,
                terminal)) {
            terminal.onError(new IllegalAccessException("This class already has a terminal"));
            return;
        }
        LatticeReceiver observed = (LatticeReceiver) DOWNSTREAM.getOpaque(this);
        if (!DOWNSTREAM.compareAndSet(this, observed, terminal)) {
            terminal.onError(new IllegalAccessException("This class already has a terminal"));
            return;
        }
        terminal.addUpstream(this);
    }

    @Override
    public void request(long num) {
        // Pushes without demand
    }

    @Override
    public void complete() {
        COMPLETE.setVolatile(this, true);
        DOWNSTREAM.setVolatile(this, null);
    }

    @Override
    public boolean isComplete() {
        return (boolean) COMPLETE.getOpaque(this);
    }
}

