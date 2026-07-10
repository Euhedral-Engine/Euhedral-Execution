package io.euhedral_execution.core.flow_control;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.FramePusher;
import java.util.concurrent.locks.LockSupport;

public abstract class LatticeHotReceiver<F extends AbstractFrame> implements FramePusher<F> {

    @Override
    public final void push(F frame) {
        int cycles = 0;
        while (true) {
            Response result = hookOnPush(frame);

            switch (result) {
                case OK -> {
                    return;
                }
                case CANCEL -> frame.throwCancelSignal();
                case TERMINATE -> {
                    frame.kill();
                    frame.throwCancelSignal();
                }
                case RETRY -> {
                    if (cycles++ < 128) {
                        Thread.onSpinWait();
                    } else if (cycles < 512) {
                        Thread.yield();
                    } else {
                        LockSupport.parkNanos(10_000);
                    }
                }
                default -> throw new NullPointerException("hookOnPush() returned null");
            }
        }
    }

    protected abstract Response hookOnPush(F frame);

    public enum Response {
        OK,
        RETRY,
        CANCEL,
        TERMINATE
    }
}
