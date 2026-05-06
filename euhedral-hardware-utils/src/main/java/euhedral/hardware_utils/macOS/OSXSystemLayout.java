package euhedral.hardware_utils.macOS;

import euhedral.hardware_utils.common.JNIClassLoader;
import euhedral.hardware_utils.common.OSName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OSXSystemLayout {

    public static final OSXSystemLayout INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(OSXSystemLayout.class);

    static {
        OSXSystemLayout layout = null;
        if (OSName.isMacOS()) {
            try {
                JNIClassLoader.load(OSXSystemLayout.class);
                layout = new OSXSystemLayout();
            } catch (Throwable t) {
                LOGGER.error("Error loading mac_system_layout", t);
            }
        }
        INSTANCE = layout;
    }

    private static native long getSysctlLong(String key);

    private static native int getSysctlInt(String key);

    private static native int getSysctlString(String key);

    private OSXSystemLayout() {

    }
}
