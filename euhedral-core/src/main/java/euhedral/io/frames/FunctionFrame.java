package euhedral.io.frames;

import euhedral.io.impl.FrameManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Setter;

/// A generic frame that applies its function to the payload and passes the result to its consumer.
@SuppressWarnings("unused")
public final class FunctionFrame<PAYLOAD, RET_VAL> extends AbstractFrame {

    final Function<PAYLOAD, RET_VAL> function;
    final Consumer<RET_VAL> callback;

    private final AtomicBoolean killSwitch;

    @Setter
    private PAYLOAD payload;

    public FunctionFrame(long idHash, Function<PAYLOAD, RET_VAL> function,
            Consumer<RET_VAL> callback) {
        this(idHash, function, callback, null, null);
    }

    public FunctionFrame(long idHash, Function<PAYLOAD, RET_VAL> function,
            Consumer<RET_VAL> callback, AtomicBoolean killSwitch,
            FrameManager<PAYLOAD, FunctionFrame<PAYLOAD, RET_VAL>> recycler) {
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
        if(killSwitch != null) {
            return !killSwitch.getOpaque();
        }
        return true;
    }

    @Override
    public void kill() {
        if(killSwitch != null) {
            killSwitch.setRelease(true);
        }
    }

    public void replace(PAYLOAD payload) {
        this.payload = payload;
    }
}
