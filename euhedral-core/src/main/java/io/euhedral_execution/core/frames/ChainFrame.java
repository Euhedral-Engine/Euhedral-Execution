package io.euhedral_execution.core.frames;

import io.euhedral_execution.core.generics.FramePusher;
import io.euhedral_execution.core.impl.FrameManager;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("rawtypes")
public abstract class ChainFrame<T extends AbstractFrame> extends AbstractFrame {

    public ChainFrame(long idHash,
            @NonNull FramePusher responseReceiver) {
        super(idHash, responseReceiver, null, null);
        Objects.requireNonNull(responseReceiver);
    }

    public ChainFrame(long idHash,
            @NonNull FramePusher responseReceiver,
            @Nullable FrameManager recycler,
            @Nullable AtomicBoolean killSwitch) {
        super(idHash, responseReceiver, recycler, killSwitch);
        Objects.requireNonNull(responseReceiver);
    }
}
