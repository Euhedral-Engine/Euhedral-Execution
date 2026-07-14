package io.euhedral_execution.hardware_utils.linux;

import io.euhedral_execution.hardware_utils.common.OSName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LinuxPaths {

    public static final Path CGROUP_V2_ROOT_PATH = Paths.get("/sys/fs/cgroup/");
    // cgroupV2
    public static final Path CGROUP_V2_USR_PATH;
    public static final Path CPU_MAX;
    public static final Path CPU_STAT;
    public static final Path EFFECTIVE_CPU;
    public static final Path CPU_PRESSURE;
    public static final Path MEMORY_MAX;
    public static final Path MEMORY_CURRENT;
    public static final Path MEMORY_STAT;
    public static final Path IO_STAT;
    public static final Path CPU_INFO_BASE = Paths.get("/sys/devices/system/cpu/");

    // Misc
    public static final Path PROC_STAT = Paths.get("/proc/stat");
    private static final Logger LOGGER = LoggerFactory.getLogger(LinuxPaths.class);

    static {
        if (OSName.isLinux()) {
            Path root;
            try {
                root = getCgroupV2UserPath();
            } catch (Throwable t) {
                root = CGROUP_V2_ROOT_PATH;
                LOGGER.error("Failed to read controllers. cgroupV1 is not supported");
            }
            CGROUP_V2_USR_PATH = root;
            CPU_MAX = resolveCgroupPath("cpu.max");
            CPU_STAT = resolveCgroupPath("cpu.stat");
            EFFECTIVE_CPU = resolveCgroupPath("cpuset.cpus.effective");
            CPU_PRESSURE = resolveCgroupPath("cpu.pressure");
            MEMORY_MAX = resolveCgroupPath("memory.max");
            MEMORY_CURRENT = resolveCgroupPath("memory.current");
            MEMORY_STAT = resolveCgroupPath("memory.stat");
            IO_STAT = resolveCgroupPath("io.stat");
        } else {
            CGROUP_V2_USR_PATH = null;
            CPU_MAX = null;
            CPU_STAT = null;
            EFFECTIVE_CPU = null;
            CPU_PRESSURE = null;
            MEMORY_MAX = null;
            MEMORY_CURRENT = null;
            MEMORY_STAT = null;
            IO_STAT = null;
        }
    }

    public static Path getCgroupV2UserPath() throws Throwable {
        Optional<String> cgroupV2Path;
        try (Stream<String> lines = Files.lines(Paths.get("/proc/self/cgroup"))) {
            cgroupV2Path =
                    lines.filter(line -> line.startsWith("0::")).map(line -> line.substring(3))
                            .findFirst();
        }

        if (cgroupV2Path.isPresent()) {
            String path = cgroupV2Path.get();

            String fsPath = "/sys/fs/cgroup" + (path.equals("/") ? "" : path);
            return Paths.get(fsPath);
        }
        throw new RuntimeException("cgroupV2 not supported.");
    }

    private static Path resolveCgroupPath(String controller) {
        Path resolvedUser = CGROUP_V2_USR_PATH.resolve(controller);
        if (resolvedUser.toFile().exists()) {
            return resolvedUser;
        }

        try {
            String controllerName = controller.split("\\.")[0];
            Path subtreeControl = CGROUP_V2_USR_PATH.getParent().resolve("cgroup.subtree_control");

            if (Files.isWritable(subtreeControl)) {
                Files.writeString(subtreeControl, "+" + controllerName, StandardOpenOption.APPEND);
            }
        } catch (Exception ignored) {
            // This block tests permissions by trying to write. If an exception is thrown, permission is denied.
        }

        return resolvedUser.toFile().exists() ? resolvedUser
                : CGROUP_V2_ROOT_PATH.resolve(controller);
    }

    private LinuxPaths() {

    }
}
