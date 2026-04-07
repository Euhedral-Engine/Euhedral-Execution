package euhedral.io.errors;

public class WorkCancelled extends RuntimeException {
    public static final WorkCancelled INSTANCE = new WorkCancelled();

    public WorkCancelled() {
        super(null, null, false, false);
    }
}
