package euhedral.hardware_utils.macOS;

import euhedral.hardware_utils.common.OSName;
import euhedral.hardware_utils.internal.JNIClassLoader;

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
