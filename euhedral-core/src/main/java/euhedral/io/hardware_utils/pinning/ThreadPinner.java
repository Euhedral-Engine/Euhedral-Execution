package euhedral.io.hardware_utils.pinning;

public abstract class ThreadPinner {
    public abstract boolean setAffinity(long[] masks);
}
