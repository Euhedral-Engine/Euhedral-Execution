package euhedral.io.resource_monitoring.providers;

import com.sun.jna.Library;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WindowsCpuLocator {
    public static final WindowsCpuLocator INSTANCE = new WindowsCpuLocator();

    private WindowsCpuLocator() {

    }

    public static final boolean LOADED;
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowsCpuLocator.class);

    static {
        boolean loaded = false;
        if(System.getProperty("os.name").toLowerCase().contains("win")) {
            try {
                // Test if the library can actually perform a call
                INSTANCE.getCpu();
                loaded = true;
            } catch (Throwable e) {
                LOGGER.warn("Unable to load JNA library for getting cpu", e);
            }
        }
        LOADED = loaded;
    }

    public int getCpu() {
        if(LOADED) {
            return CLibrary.INSTANCE.GetCurrentProcessorNumber();
        }
        return -1;
    }

    private interface CLibrary extends Library {
        CLibrary INSTANCE = Native.load("kernel32", CLibrary.class);

        int GetCurrentProcessorNumber();
    }
}
