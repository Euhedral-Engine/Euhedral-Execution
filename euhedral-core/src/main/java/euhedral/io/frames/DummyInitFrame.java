package euhedral.io.frames;

public class DummyInitFrame extends AbstractFrame {
    public static final DummyInitFrame INSTANCE = new DummyInitFrame();

    private DummyInitFrame() {
        super(0, null);
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
