package io.euhedral_execution.core.frames;

import io.euhedral_execution.core.impl.FrameManager;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/// A generic frame that applies its function to the payload and passes the result to its consumer.
///
/// @param <P> Data type to be passed to the function
/// @param <R> Data type that is returned by the function
@SuppressWarnings("unused")
public final class FunctionFrame<P, R> extends AbstractFrame {

    final Function<P, R> function;
    final Consumer<R> consumer;

    private P payload;

    public FunctionFrame(long idHash, Function<P, R> function,
            Consumer<R> consumer, P payload) {
        this(idHash, function, consumer, payload, null, null);
    }

    public FunctionFrame(long idHash, Function<P, R> function,
            Consumer<R> consumer, P payload, FrameManager<P, FunctionFrame<P, R>> recycler, AtomicBoolean killSwitch) {
        super(idHash, recycler, killSwitch);
        Objects.requireNonNull(function);
        Objects.requireNonNull(consumer);
        this.function = function;
        this.consumer = consumer;
        this.payload = payload;
    }

    @Override
    public void execute() {
        this.consumer.accept(this.function.apply(this.payload));
    }

    public void replace(P payload) {
        this.payload = payload;
    }
}
