package euhedral.io.frames;

import euhedral.io.utils.MpscFrameRecycler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.Setter;

public class ConsumerFrame extends AbstractFrame {

    private final Consumer<Object> consumer;
    private final AtomicBoolean killSwitch;
    @Setter
    private Object payload;

    public ConsumerFrame(long idHash, Consumer<Object> consumer, AtomicBoolean killSwitch,
            MpscFrameRecycler recycler) {
        super(idHash, recycler);
        this.consumer = consumer;
        this.killSwitch = killSwitch;
    }

    public void consume() {
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

    public void replace(Object object) {
        this.payload = object;
    }
}
