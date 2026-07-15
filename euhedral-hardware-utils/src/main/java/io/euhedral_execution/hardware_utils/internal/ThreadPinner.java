package io.euhedral_execution.hardware_utils.internal;

import io.euhedral_execution.hardware_utils.linux.LinuxAffinity;
import io.euhedral_execution.hardware_utils.osx.OSXAffinity;
import io.euhedral_execution.hardware_utils.windows.WindowsAffinity;

public abstract sealed class ThreadPinner permits LinuxAffinity, OSXAffinity, WindowsAffinity {

    public abstract int getCpu();

    public abstract boolean setAffinity(long[] masks);

    public abstract boolean setTimerResolution(long nanos);
}
