package euhedral.io.resource_monitoring.providers;

import java.util.Arrays;

public class OSResourceProviderPicker {

    public static final OSName OS;
    public static final ResourceProvider INSTANCE;

    static {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("linux")) {
            OS = OSName.LINUX;
            ResourceProvider instance = null;
            try {
                instance = new CgroupV2Resources();
            } catch (Throwable t) {
                System.err.println("cgroupV2 is not supported");
                System.err.println(Arrays.toString(t.getStackTrace()));
            }
            INSTANCE = instance;
        } else if (os.contains("win")) {
            OS = OSName.WINDOWS;
            INSTANCE = new WindowsResources();
        } else if (os.contains("mac")) {
            OS = OSName.OSX;
            INSTANCE = new MacOSResources();
        } else {
            OS = OSName.UNKNOWN;
            INSTANCE = null;
        }
    }

    public enum OSName {
        LINUX, WINDOWS, OSX, UNKNOWN
    }
}
