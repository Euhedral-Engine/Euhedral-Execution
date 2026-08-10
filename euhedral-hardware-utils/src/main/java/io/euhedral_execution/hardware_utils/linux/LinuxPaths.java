package io.euhedral_execution.hardware_utils.linux;

import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.Constants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Discovers Linux paths and execution modes (cgroup v1, cgroup v2, hybrid, bare-host) strictly
/// read-only.
public final class LinuxPaths {

    public static final Path CGROUP_V2_ROOT_PATH = Paths.get("/sys/fs/cgroup");
    public static final Path PROC_STAT = Paths.get("/proc/stat");
    public static final Path PROC_MEMINFO = Paths.get("/proc/meminfo");
    public static final Path PROC_DISKSTATS = Paths.get("/proc/diskstats");
    public static final Path PROC_SELF_MOUNTINFO = Paths.get("/proc/self/mountinfo");
    public static final Path PROC_SELF_CGROUP = Paths.get("/proc/self/cgroup");
    public static final Path CPU_INFO_BASE = Paths.get("/sys/devices/system/cpu/");
    public static final Path THERMAL_BASE = Paths.get("/sys/class/thermal/");
    public static final Path HWMON_BASE = Paths.get("/sys/class/hwmon/");
    public static final Path CGROUP_V2_USR_PATH;
    public static final Path CPU_MAX;
    public static final Path CPU_STAT;
    public static final Path EFFECTIVE_CPU;
    public static final Path CPU_PRESSURE;
    public static final Path MEMORY_MAX;
    public static final Path MEMORY_CURRENT;
    public static final Path MEMORY_STAT;
    public static final Path IO_STAT;

    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(LinuxPaths.class));

    static {
        if (OSName.isLinux()) {
            LinuxPaths defaultPaths = new LinuxPaths();
            CGROUP_V2_USR_PATH =
                    defaultPaths.cgroupV2UserPath != null ? defaultPaths.cgroupV2UserPath : CGROUP_V2_ROOT_PATH;
            CPU_MAX = defaultPaths.resolveV2Path("cpu.max");
            CPU_STAT = defaultPaths.resolveV2Path("cpu.stat");
            EFFECTIVE_CPU = defaultPaths.resolveV2Path("cpuset.cpus.effective");
            CPU_PRESSURE = defaultPaths.resolveV2Path("cpu.pressure");
            MEMORY_MAX = defaultPaths.resolveV2Path("memory.max");
            MEMORY_CURRENT = defaultPaths.resolveV2Path("memory.current");
            MEMORY_STAT = defaultPaths.resolveV2Path("memory.stat");
            IO_STAT = defaultPaths.resolveV2Path("io.stat");
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

    @Getter
    private final CgroupMode mode;

    private final Path cgroupV2UserPath;
    private final Map<String, Path> v1ControllerMounts;
    private final Map<String, String> v1SelfPaths;

    public LinuxPaths() {
        this(PROC_SELF_MOUNTINFO, PROC_SELF_CGROUP, CGROUP_V2_ROOT_PATH);
    }

    public LinuxPaths(Path mountinfoPath, Path cgroupPath, Path cgroupV2Root) {
        if (!OSName.isLinux() && !Files.exists(mountinfoPath) && !Files.exists(cgroupPath)) {
            this.mode = CgroupMode.BARE_HOST;
            this.cgroupV2UserPath = null;
            this.v1ControllerMounts = Map.of();
            this.v1SelfPaths = Map.of();
            return;
        }

        Map<String, Path> v1Mounts = new HashMap<>();

        if (Files.exists(mountinfoPath)) {
            try (Stream<String> lines = Files.lines(mountinfoPath)) {
                lines.forEach(line -> {
                    String[] parts = line.split(" ");
                    if (parts.length >= 9) {
                        int hyphenIndex = -1;
                        for (int i = 4; i < parts.length; i++) {
                            if ("-".equals(parts[i])) {
                                hyphenIndex = i;
                                break;
                            }
                        }
                        if (hyphenIndex != -1 && hyphenIndex + 1 < parts.length) {
                            String fsType = parts[hyphenIndex + 1];
                            String mountPoint = parts[4];
                            if ("cgroup".equals(fsType)) {
                                String mountOpts = parts[5];
                                String superOpts = parts.length > hyphenIndex + 3 ? parts[hyphenIndex + 3] : "";
                                String combinedOpts = mountOpts + "," + superOpts;
                                for (String opt : combinedOpts.split(",")) {
                                    if (!opt.isBlank()
                                            && !"rw".equals(opt)
                                            && !"ro".equals(opt)
                                            && !"nosuid".equals(opt)
                                            && !"nodev".equals(opt)
                                            && !"noexec".equals(opt)
                                            && !"relatime".equals(opt)) {
                                        v1Mounts.putIfAbsent(opt, Paths.get(mountPoint));
                                    }
                                }
                            }
                        }
                    }
                });
            } catch (Exception e) {
                LOGGER.warn("Failed to parse mountinfo: {}", e.getMessage());
            }
        }

        Map<String, String> selfV1 = new HashMap<>();
        String[] selfV2SubpathHolder = new String[1];

        if (Files.exists(cgroupPath)) {
            try (Stream<String> lines = Files.lines(cgroupPath)) {
                lines.forEach(line -> {
                    String[] parts = line.split(":", 3);
                    if (parts.length == 3) {
                        String hierarchy = parts[0];
                        String subsystems = parts[1];
                        String path = parts[2];
                        if ("0".equals(hierarchy) && subsystems.isEmpty()) {
                            selfV2SubpathHolder[0] = path;
                        } else if (!subsystems.isEmpty()) {
                            for (String sub : subsystems.split(",")) {
                                selfV1.put(sub, path);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                LOGGER.warn("Failed to parse self cgroup: {}", e.getMessage());
            }
        }

        String selfV2Subpath = selfV2SubpathHolder[0];

        this.v1ControllerMounts = Map.copyOf(v1Mounts);
        this.v1SelfPaths = Map.copyOf(selfV1);

        if (selfV2Subpath != null && Files.exists(cgroupV2Root)) {
            Path userPath = "/".equals(selfV2Subpath) ? cgroupV2Root : cgroupV2Root.resolve(selfV2Subpath.substring(1));
            if (!Files.exists(userPath)) {
                userPath = cgroupV2Root;
            }
            this.cgroupV2UserPath = userPath;

            if (!selfV1.isEmpty()) {
                this.mode = CgroupMode.HYBRID;
            } else {
                this.mode = CgroupMode.CGROUP_V2;
            }
        } else if (!selfV1.isEmpty()) {
            this.mode = CgroupMode.CGROUP_V1;
            this.cgroupV2UserPath = null;
        } else {
            this.mode = CgroupMode.BARE_HOST;
            this.cgroupV2UserPath = null;
        }
    }

    public static Path getCgroupV2UserPath() throws Throwable {
        Optional<String> cgroupV2Path;
        try (Stream<String> lines = Files.lines(Paths.get("/proc/self/cgroup"))) {
            cgroupV2Path = lines.filter(line -> line.startsWith("0::"))
                    .map(line -> line.substring(3))
                    .findFirst();
        }

        if (cgroupV2Path.isPresent()) {
            String path = cgroupV2Path.get();
            String fsPath = "/sys/fs/cgroup" + (path.equals("/") ? "" : path);
            return Paths.get(fsPath);
        }
        throw new RuntimeException("cgroupV2 not supported.");
    }

    public Path resolveV2Path(String filename) {
        if (cgroupV2UserPath != null) {
            Path p = cgroupV2UserPath.resolve(filename);
            if (Files.exists(p)) {
                return p;
            }
            Path rootP = CGROUP_V2_ROOT_PATH.resolve(filename);
            if (Files.exists(rootP)) {
                return rootP;
            }
        }
        return null;
    }

    public Path resolveV1Path(String controller, String filename) {
        Path mount = v1ControllerMounts.get(controller);
        if (mount != null) {
            String subpath = v1SelfPaths.get(controller);
            if (subpath != null && !subpath.equals("/")) {
                Path userPath = mount.resolve(subpath.startsWith("/") ? subpath.substring(1) : subpath)
                        .resolve(filename);
                if (Files.exists(userPath)) {
                    return userPath;
                }
            }
            Path rootPath = mount.resolve(filename);
            if (Files.exists(rootPath)) {
                return rootPath;
            }
        }
        return null;
    }

    public enum CgroupMode {
        CGROUP_V2,
        CGROUP_V1,
        HYBRID,
        BARE_HOST
    }
}
