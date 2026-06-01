package euhedral.io.frames;

/// A frame that cannot be executed or instantiated. Used for `firstTouch()` sequences like filling
/// queues.
public final class DummyInitFrame extends AbstractFrame {

    public static final DummyInitFrame INSTANCE = new DummyInitFrame();

    private DummyInitFrame() {
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
