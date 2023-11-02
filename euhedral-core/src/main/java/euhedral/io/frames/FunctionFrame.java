package euhedral.io.frames;

import euhedral.io.impl.FrameManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Setter;

public class FunctionFrame<T, R> extends AbstractFrame {

    final Function<T, R> function;
    final Consumer<R> callback;

    private final AtomicBoolean killSwitch;

    @Setter
    private T payload;


    public FunctionFrame(long idHash, Function<T, R> function, Consumer<R> callback, AtomicBoolean killSwitch,
            FrameManager<T, FunctionFrame<T, R>> recycler) {
        super(idHash, recycler);
        this.function = function;
        this.callback = callback;
        this.killSwitch = killSwitch;
    }

    @Override
    public void execute() {
        callback.accept(function.apply(payload));
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

    public void replace(T payload) {
        this.payload = payload;
    }
}
