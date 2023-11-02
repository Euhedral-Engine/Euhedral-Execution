package euhedral.io.frames;

public class CancelFrame extends RuntimeException {
    public final AbstractFrame payload;

    public CancelFrame(AbstractFrame frame) {
        super(null, null, false, false);
        this.payload = frame;
    }
}
