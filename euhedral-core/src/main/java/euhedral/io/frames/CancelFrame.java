package euhedral.io.frames;

/// This class is thrown as a cancellation signal. This signal is automatically handled by the
/// [ExecutionManager][euhedral.io.ExecutionManager] and
/// [AbstractExecutor][euhedral.io.generics.AbstractExecutor].
public class CancelFrame extends RuntimeException {

    public final AbstractFrame payload;

    public CancelFrame(AbstractFrame frame) {
        super(null, null, false, false);
        this.payload = frame;
    }
}
