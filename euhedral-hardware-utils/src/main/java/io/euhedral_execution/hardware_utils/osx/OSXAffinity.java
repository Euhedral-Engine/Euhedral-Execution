package io.euhedral_execution.hardware_utils.osx;

import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import io.euhedral_execution.hardware_utils.internal.ThreadPinner;

public final class OSXAffinity extends ThreadPinner {

    public static final OSXAffinity INSTANCE;

    static {
        JNIClassLoader.load();

        OSXAffinity instance = null;
        if(OSName.isMacOS()) {
            instance = new OSXAffinity();
        }
        INSTANCE = instance;
    }

    private OSXAffinity() {
        super(io.euhedral_execution.hardware_utils.AffinityCapability.LOCALITY_HINT,
                OSXAffinity::setThreadAffinity);
    }

    @Override
    public native int getCpu();

    /// Applies one process-visible CPU ordinal as a macOS scheduler-locality tag.
    ///
    /// This is a preference only and does not promise exact CPU placement.
    @Override
    public boolean setAffinity(long[] masks) {
        return OSXAffinityCalls.applyOrdinal(masks, OSXAffinity::setThreadAffinity);
    }

    @Override
    public boolean setTimerResolution(long nanos) {
        if(nanos < 0) {
            throw new RuntimeException("Cannot set negative resolution: " + nanos);
        }
        nanos = Math.max(1, nanos);
        return setThreadTickPolicy(nanos);
    }

    private static native int setThreadAffinity(long[] masks);

    private static native boolean setThreadTickPolicy(long nanos);

}
