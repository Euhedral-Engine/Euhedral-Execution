package euhedral.io.frames;

import euhedral.io.impl.FrameManager;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/// A generic frame that consumes its payload when executed.
public final class ConsumerFrame<T> extends AbstractFrame {

    private final Consumer<T> consumer;
    private final AtomicBoolean killSwitch;

    private T payload;

    public ConsumerFrame(long idHash, Consumer<T> consumer, T payload) {
        this(idHash, consumer, payload, null, null);
    }

    public ConsumerFrame(long idHash, Consumer<T> consumer, T payload, AtomicBoolean killSwitch,
            FrameManager<T, ConsumerFrame<T>> recycler) {
        super(idHash, recycler);
        Objects.requireNonNull(consumer);
        Objects.requireNonNull(payload);
        this.consumer = consumer;
        this.killSwitch = killSwitch;
        this.payload = payload;
    }

    @Override
    public void execute() {
        this.consumer.accept(this.payload);
    }

    @Override
    public long getSizeBytes() {
        return 256;
    }

    @Override
    public boolean isAlive() {
        if(this.killSwitch != null) {
            return !killSwitch.getOpaque();
        }
        return true;
    }

    @Override
    public void kill() {
        if(this.killSwitch != null) {
            killSwitch.setRelease(true);
        }
    }

    public void replace(T object) {
        this.payload = object;
    }
}
