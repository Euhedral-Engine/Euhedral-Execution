package euhedral.io.frames;

/// A frame that cannot be executed or instantiated. Used for `firstTouch()` sequences like filling
/// queues.
public final class DummyFrame extends AbstractFrame {

    public static final DummyFrame INSTANCE = new DummyFrame();

    private DummyFrame() {
        super(0, null);
    }

    @Override
    public void execute() {

    }

    @Override
    public long getSizeBytes() {
        return 1024;
    }

    @Override
    public boolean isAlive() {
        return false;
    }

    @Override
    public void kill() {

    }
}
