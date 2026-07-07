package euhedral.io.flow_control;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;

/// Used for pushing frames from one stage to the next. Assumes that only one thread will control
/// the push side. This class can only have one downstream.
@SuppressWarnings("unused")
public final class LatticeHotSource implements LatticeSource, Consumer<AbstractFrame> {

    private static final VarHandle COMPLETE;
    private static final VarHandle TERMINAL;

    static {
        try {
            COMPLETE = MethodHandles.lookup()
                    .findVarHandle(LatticeHotSource.class, "complete", boolean.class);
            TERMINAL = MethodHandles.lookup()
                    .findVarHandle(LatticeHotSource.class, "terminal", LatticeReceiver.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Consumer<AbstractFrame> applyToEach;

    boolean complete = false;
    LatticeReceiver terminal = null;

    public LatticeHotSource() {
        this.applyToEach = null;
    }

    public LatticeHotSource(
            Consumer<AbstractFrame> applyToEach) {
        this.applyToEach = applyToEach;
    }

    @Override
    public long pull(Consumer<AbstractFrame> consumer, long demand) {
        return 0;
    }

    @Override
    public void accept(AbstractFrame frame) {
        if (TERMINAL.getOpaque(this) == null || (boolean) COMPLETE.getOpaque(this)) {
            return;
        }

        if (this.applyToEach != null) {
            this.applyToEach.accept(frame);
        }

        LatticeReceiver terminal = (LatticeReceiver) TERMINAL.getOpaque(this);
        if (terminal != null) {
            terminal.push(frame);
        }
    }

    @Override
    public void addDownstream(LatticeReceiver terminal) {
        if (!COMPLETE.compareAndSet(this, true, false) && !TERMINAL.compareAndSet(this, null,
                terminal)) {
            terminal.onError(new IllegalAccessException("This class already has a terminal"));
            return;
        }
        LatticeReceiver observed = (LatticeReceiver) TERMINAL.getOpaque(this);
        if (!TERMINAL.compareAndSet(this, observed, terminal)) {
            terminal.onError(new IllegalAccessException("This class already has a terminal"));
            return;
        }
        terminal.addUpstream(this);
    }

    @Override
    public void request(long num) {

    }

    @Override
    public void complete() {
        COMPLETE.setVolatile(this, true);
        TERMINAL.setVolatile(this, null);
    }
}

