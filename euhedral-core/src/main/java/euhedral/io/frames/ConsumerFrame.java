package euhedral.io.frames;

import euhedral.io.utils.MpscFrameRecycler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.Setter;

public class ConsumerFrame extends AbstractFrame {

    private final String id;
    private final Consumer<Object> consumer;
    private final AtomicBoolean killSwitch;
    @Setter
    private Object payload;

    public ConsumerFrame(String id, long idHash, long destinationHash, Consumer<Object> consumer, AtomicBoolean killSwitch,
            MpscFrameRecycler recycler) {
        super(idHash, destinationHash, recycler);
        this.id = id;
        this.consumer = consumer;
        this.killSwitch = killSwitch;
    }

    public void consume() {
        consumer.accept(payload);
    }

    @Override
    public String getId() {
        return id;
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
        setCancelledExecution(false);
    }
}
