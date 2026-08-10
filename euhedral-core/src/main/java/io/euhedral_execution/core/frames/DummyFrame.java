package io.euhedral_execution.core.frames;

/// A frame that cannot be executed or instantiated. Used for `firstTouch()` sequences like filling
/// queues.
public final class DummyFrame extends AbstractFrame {

    public static final DummyFrame INSTANCE = new DummyFrame();

    private DummyFrame() {
        super(0);
    }
}
