package euhedral.hardware_utils.internal;

import euhedral.hardware_utils.linux.LinuxAffinity;
import euhedral.hardware_utils.macOS.OSXAffinity;
import euhedral.hardware_utils.windows.WindowsAffinity;

public abstract sealed class ThreadPinner permits LinuxAffinity, OSXAffinity, WindowsAffinity {

    public abstract int getCpu();

    public abstract boolean setAffinity(long[] masks);

    public abstract boolean setTimerResolution(long nanos);
}
