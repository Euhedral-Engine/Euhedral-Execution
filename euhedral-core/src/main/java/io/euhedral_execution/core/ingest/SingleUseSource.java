package io.euhedral_execution.core.ingest;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.function.Consumer;

@SuppressWarnings("unused")
public class SingleUseSource implements LatticeSource {
    private static final VarHandle COMPLETE;
    private static final VarHandle RECEIVER;

    static {
        try {
            COMPLETE = MethodHandles.lookup().findVarHandle(SingleUseSource.class, "complete", boolean.class);
            RECEIVER = MethodHandles.lookup().findVarHandle(SingleUseSource.class, "receiver", LatticeReceiver.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static SingleUseSource wrap(AbstractFrame frame) {
        Objects.requireNonNull(frame);
        return new SingleUseSource(frame);
    }

    private final AbstractFrame frame;

    private LatticeReceiver receiver;
    private boolean complete;

    private SingleUseSource(AbstractFrame frame) {
        this.frame = frame;
    }

    @Override
    public long pull(Consumer<AbstractFrame> consumer, long demand) {
        if(demand <= 0) {
            return 0;
        }
        if(COMPLETE.compareAndSet(this, false, true)) {
            consumer.accept(frame);
            complete();
            return 1;
        }
        return 0;
    }

    @Override
    public void request(long demand) {
        if(demand <= 0) {
            return;
        }

        if(COMPLETE.compareAndSet(this, false, true)) {
            this.receiver.push(this.frame);
            complete();
        }
    }

    @Override
    public void addDownstream(LatticeReceiver downstream) {
        Objects.requireNonNull(downstream);
        if(!RECEIVER.compareAndSet(this, null, downstream)) {
            downstream.onError(new IllegalStateException("This class can only have one downstream"));
        }
    }

    @Override
    public void complete() {
        LatticeReceiver receiver = (LatticeReceiver) RECEIVER.getAndSetRelease(this, null);
        if(receiver != null) {
            receiver.onComplete();
        }
    }
}
