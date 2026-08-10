package io.euhedral_execution.core.frames;

import io.euhedral_execution.core.generics.FramePusher;
import io.euhedral_execution.core.impl.FrameManager;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("unused")
public final class CallbackFrame<T, R> extends AbstractFrame {

    private final Function<T, R> function;

    @Getter
    private T payload;

    @Getter
    private R retVal;

    public CallbackFrame(
            long idHash,
            T payload,
            @NonNull Function<T, R> function,
            @NonNull FramePusher<CallbackFrame<T, R>> responseReceiver) {
        super(idHash, responseReceiver, null, null);
        Objects.requireNonNull(function);
        this.function = function;
        this.payload = payload;
    }

    public CallbackFrame(
            long idHash,
            T payload,
            @NonNull Function<T, R> function,
            @NonNull FramePusher<CallbackFrame<T, R>> responseReceiver,
            @Nullable FrameManager<T, CallbackFrame<T, R>> recycler,
            @Nullable AtomicBoolean killSwitch) {
        super(idHash, responseReceiver, recycler, killSwitch);
        Objects.requireNonNull(function);
        this.function = function;
        this.payload = payload;
    }

    @Override
    public void execute() {
        this.retVal = this.function.apply(this.payload);
        giveToReceiver(this);
    }

    @Override
    public void doFinally() {
        // Intentionally empty. recycle() must be called manually
    }

    public void replace(T payload) {
        this.payload = payload;
        this.retVal = null;
    }
}
