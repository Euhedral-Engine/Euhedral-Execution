package euhedral.io.frames;

import euhedral.io.utils.MpscFrameRecycler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Setter;

public class FunctionFrame extends AbstractFrame {

    private final String id;

    final Function<Object, Object> function;
    final Consumer<Object> callback;

    private final AtomicBoolean killSwitch;

    @Setter
    private Object payload;


    public FunctionFrame(String id, long idHash, long destinationHash, Function<Object, Object> function, Consumer<Object> callback, AtomicBoolean killSwitch,
            MpscFrameRecycler recycler) {
        super(idHash, destinationHash, recycler);
        this.id = id;
        this.function = function;
        this.callback = callback;
        this.killSwitch = killSwitch;
    }

    public void apply() {
        callback.accept(function.apply(payload));
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

    public <T> void replace(T payload) {
        this.payload = payload;
        setCancelledExecution(false);
    }
}
