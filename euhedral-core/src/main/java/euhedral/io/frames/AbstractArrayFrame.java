package euhedral.io.frames;

public abstract class AbstractArrayFrame extends AbstractFrame {
    protected final AbstractFrame[] frames;
    protected final long sizeBytes;

    public AbstractArrayFrame(long idHash, AbstractFrame[] frames) {
        super(idHash, null);

        this.frames = frames;

        long sizeBytes = 0;
        for (AbstractFrame frame : frames) {
            sizeBytes += frame.getSizeBytes();
        }
        this.sizeBytes = sizeBytes;
    }

    @Override
    public void execute() {
        for(AbstractFrame frame : this.frames) {
            frame.execute();
        }
    }

    @Override
    public final long getSizeBytes() {
        return sizeBytes;
    }
}
