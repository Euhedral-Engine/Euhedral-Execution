package io.euhedral_execution.core.ingest;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.utils.CommonVarHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

@SuppressWarnings("unused")
public class SingleUseSource implements LatticeSource {

    private static final VarHandle COMPLETE = CommonVarHandles.complete(SingleUseSource.class);
    private static final VarHandle DOWNSTREAM = CommonVarHandles.downstream(SingleUseSource.class);
    private final AbstractFrame frame;
    private LatticeReceiver downstream;
    private boolean complete;
    private SingleUseSource(AbstractFrame frame) {
        this.frame = frame;
    }

    public static SingleUseSource wrap(AbstractFrame frame) {
        Objects.requireNonNull(frame);
        return new SingleUseSource(frame);
    }

    @Override
    public long pull(Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
        if (demand <= 0) {
            return 0;
        }

        if (COMPLETE.compareAndSet(this, false, true)) {
            consumer.accept(frame);
            complete();
            return 1;
        }
        return 0;
    }

    @Override
    public void request(long demand) {
        if (demand <= 0) {
            return;
        }

        if (COMPLETE.compareAndSet(this, false, true)) {
            this.downstream.push(this.frame);
            complete();
        }
    }

    @Override
    public void addDownstream(LatticeReceiver downstream) {
        Objects.requireNonNull(downstream);
        if (!DOWNSTREAM.compareAndSet(this, null, downstream)) {
            downstream.onError(new IllegalStateException("This class can only have one downstream"));
        }
    }

    @Override
    public void complete() {
        LatticeReceiver receiver = (LatticeReceiver) DOWNSTREAM.getAndSetRelease(this, null);
        if (receiver != null) {
            receiver.onComplete();
        }
    }

    @Override
    public boolean isComplete() {
        return (boolean) COMPLETE.getOpaque(this);
    }
}
