package io.euhedral_execution.core.frames;

import io.euhedral_execution.core.impl.FrameManager;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/// A generic frame that consumes its payload when executed.
///
/// @param <T> Data type to consume
@SuppressWarnings("unused")
public final class ConsumerFrame<T> extends AbstractFrame {

    private final Consumer<T> consumer;

    private T payload;

    public ConsumerFrame(long idHash, Consumer<T> consumer, T payload) {
        this(idHash, consumer, payload, null, null);
    }

    public ConsumerFrame(long idHash, Consumer<T> consumer, T payload, AtomicBoolean killSwitch,
            FrameManager<T, ConsumerFrame<T>> recycler) {
        super(idHash, recycler, killSwitch);
        Objects.requireNonNull(consumer);
        Objects.requireNonNull(payload);
        this.consumer = consumer;
        this.payload = payload;
    }

    @Override
    public void execute() {
        this.consumer.accept(this.payload);
    }

    public void replace(T object) {
        this.payload = object;
    }
}
