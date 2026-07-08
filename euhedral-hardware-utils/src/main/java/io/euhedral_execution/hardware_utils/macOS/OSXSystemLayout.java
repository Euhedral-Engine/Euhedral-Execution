package io.euhedral_execution.hardware_utils.macOS;

import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;

public final class OSXSystemLayout {

    public static final OSXSystemLayout INSTANCE;

    static {
        JNIClassLoader.load();

        OSXSystemLayout layout = null;
        if (OSName.isMacOS()) {
            layout = new OSXSystemLayout();
        }
        INSTANCE = layout;
    }

    private static native long getSysctlLong(String key);

    private static native int getSysctlInt(String key);

    private static native int getSysctlString(String key);

    private OSXSystemLayout() {

    }
}
