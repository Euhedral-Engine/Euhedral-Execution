package euhedral.io.frames;

import euhedral.io.impl.FrameManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.Setter;

public class ConsumerFrame<T> extends AbstractFrame {

    private final Consumer<T> consumer;
    private final AtomicBoolean killSwitch;
    @Setter
    private T payload;

    public ConsumerFrame(long idHash, Consumer<T> consumer, AtomicBoolean killSwitch,
            FrameManager<T, ConsumerFrame<T>> recycler) {
        super(idHash, recycler);
        this.consumer = consumer;
        this.killSwitch = killSwitch;
    }

    @Override
    public void execute() {
        consumer.accept(payload);
    }

    @Override
    public long getSizeBytes() {
        return 256;
    }

    @Override
    public boolean isAlive() {
        return !killSwitch.get();
    }

    @Override
    public void kill() {
        killSwitch.set(true);
    }

    public void replace(T object) {
        this.payload = object;
    }
}
