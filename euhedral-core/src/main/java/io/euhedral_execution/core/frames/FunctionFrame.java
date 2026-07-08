package io.euhedral_execution.core.frames;

import io.euhedral_execution.core.impl.FrameManager;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/// A generic frame that applies its function to the payload and passes the result to its consumer.
///
/// @param <PAYLOAD> Data type to be passed to the function
/// @param <RET_VAL> Data type that is returned by the function
@SuppressWarnings("unused")
public final class FunctionFrame<PAYLOAD, RET_VAL> extends AbstractFrame {

    final Function<PAYLOAD, RET_VAL> function;
    final Consumer<RET_VAL> consumer;

    private final AtomicBoolean killSwitch;

    private PAYLOAD payload;

    public FunctionFrame(long idHash, Function<PAYLOAD, RET_VAL> function,
            Consumer<RET_VAL> consumer, PAYLOAD payload) {
        this(idHash, function, consumer, payload, null, null);
    }

    public FunctionFrame(long idHash, Function<PAYLOAD, RET_VAL> function,
            Consumer<RET_VAL> consumer, PAYLOAD payload, AtomicBoolean killSwitch,
            FrameManager<PAYLOAD, FunctionFrame<PAYLOAD, RET_VAL>> recycler) {
        super(idHash, recycler);
        Objects.requireNonNull(function);
        Objects.requireNonNull(consumer);
        this.function = function;
        this.consumer = consumer;
        this.killSwitch = killSwitch;
        this.payload = payload;
    }

    @Override
    public void execute() {
        this.consumer.accept(this.function.apply(this.payload));
    }

    @Override
    public boolean isAlive() {
        if(this.killSwitch != null) {
            return !this.killSwitch.getOpaque();
        }
        return true;
    }

    @Override
    public void kill() {
        if(this.killSwitch != null) {
            this.killSwitch.setRelease(true);
        }
    }

    public void replace(PAYLOAD payload) {
        this.payload = payload;
    }
}
