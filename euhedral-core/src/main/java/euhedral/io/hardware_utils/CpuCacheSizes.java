package euhedral.io.hardware_utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;

public abstract class CpuCacheSizes {
    public static final long DEFAULT_L1 = 32L * 1024L;
    public static final long DEFAULT_L2 = 256L * 1024L;
    public static final long DEFAULT_L3 = 4L * 1024L * 1024L;

    private static final Path BASE_PATH = Paths.get("/sys/devices/system/cpu/");
    private static final Int2ObjectArrayMap<CpuCacheLayout> CPU_CACHE =
            new Int2ObjectArrayMap<>(Runtime.getRuntime().availableProcessors());

    static {
        if(System.getProperty("os.name").toLowerCase().contains("linux")) {
            try (var cpuDirs = Files.list(BASE_PATH)) {
                cpuDirs.filter(p -> p.getFileName().toString().matches("cpu\\d+"))
                        .forEach(cpuDir -> {
                            Path cachePath = cpuDir.resolve("cache");
                            if (!Files.exists(cachePath)) {
                                return;
                            }
                            int cpu = getCpu(cpuDir.getFileName().toString());
                            long[] size = new long[3];
                            int[] shared = new int[3];

                            try (var indexes = Files.list(cachePath)) {
                                indexes.forEach(index -> {
                                    try {
                                        String type = read(index.resolve("type"));
                                        if (type.toLowerCase().startsWith("instruction")) {
                                            return;
                                        }

                                        int level = Integer.parseInt(read(index.resolve("level")));
                                        size[level - 1] = toBytes(read(index.resolve("size")));
                                        shared[level - 1] =
                                                parseShared(read(index.resolve("shared_cpu_map")));
                                    } catch (IOException ignored) {}
                                });
                            } catch (Exception ignored) {}
                            CPU_CACHE.put(cpu,
                                    new CpuCacheLayout(
                                            cpu,
                                            size[0] <= 0 ? DEFAULT_L1 : size[0],
                                            size[1] <= 0 ? DEFAULT_L2 : size[1],
                                            size[2] <= 0 ? DEFAULT_L3 : size[2],
                                            shared[0], shared[1], shared[2]
                                    )
                            );
                        });
            } catch (IOException ignored) {
            }
        }
    }

    private static int getCpu(String folder) {
        int cpu = 0;
        for(int i = 3; i < folder.length(); i++) {
            char c = folder.charAt(i);
            if(c > '9') {
                break;
            }
            cpu *= 10;
            cpu += c - '0';
        }
        return cpu;
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p).trim();
    }

    private static long toBytes(String size) {
        char lastChar = size.charAt(size.length() - 1);
        if(lastChar == 'K' || lastChar == 'k') {
            return Long.parseLong(size.substring(0, size.length() - 1)) * 1024;
        } else if(lastChar == 'M' || lastChar == 'm') {
            return Long.parseLong(size.substring(0, size.length() - 1)) * 1024 * 1024;
        }
        return Long.parseLong(size);
    }

    private static int parseShared(String shared) {
        int cpus = 0;
        String[] groups = shared.split(",");
        for(String g : groups) {
            cpus += Long.bitCount(Long.parseUnsignedLong(g, 16));
        }
        return cpus;
    }

    public static CpuCacheLayout getCacheLayout(int cpu) {
        CpuCacheLayout layout =  CPU_CACHE.get(cpu);
        if(layout == null) {
            return new CpuCacheLayout(cpu, DEFAULT_L1, DEFAULT_L2, DEFAULT_L3, 1, 1, Runtime.getRuntime().availableProcessors());
        }
        return layout;
    }

    /// Shares of size 1 means only this cpu uses the level of cache.
    public record CpuCacheLayout(int cpu, long bytesL1, long bytesL2, long bytesL3, int sharesL1, int sharesL2, int sharesL3) {}
}
