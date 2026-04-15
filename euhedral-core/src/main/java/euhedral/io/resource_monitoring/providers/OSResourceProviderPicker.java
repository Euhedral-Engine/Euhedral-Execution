package euhedral.io.resource_monitoring.providers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class OSResourceProviderPicker {

    public static final OSName OS;
    public static final ResourceProvider INSTANCE;

    static {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("linux")) {
            OS = OSName.LINUX;
            ResourceProvider instance = null;
            try {
                Optional<String> cgroupV2Path = Files.lines(Paths.get("/proc/self/cgroup"))
                        .filter(line -> line.startsWith("0::"))
                        .map(line -> line.substring(3))
                        .findFirst();

                if (cgroupV2Path.isPresent()) {
                    String path = cgroupV2Path.get();

                    String fsPath = "/sys/fs/cgroup" + (path.equals("/") ? "" : path);
                    Path cgroupPath = Paths.get(fsPath);
                    instance = new CgroupV2Resources(cgroupPath);
                }
            } catch (Exception ignored) {
                System.err.println("cgroupV1 is not supported");
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
        LINUX,
        WINDOWS,
        OSX,
        UNKNOWN
    }
}
