package io.euhedral_execution.hardware_utils.common;

public abstract class ThreadPinner {

    public abstract int getCpu();

    public abstract boolean setAffinity(long[] masks);

    public abstract boolean setTimerResolution(long nanos);
}
