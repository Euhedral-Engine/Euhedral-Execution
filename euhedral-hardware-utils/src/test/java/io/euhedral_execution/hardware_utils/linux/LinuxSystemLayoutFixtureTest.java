package io.euhedral_execution.hardware_utils.linux;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.topology.internal.CoreKind;
import io.euhedral_execution.hardware_utils.topology.internal.LogicalCpu;
import io.euhedral_execution.hardware_utils.topology.internal.TopologyInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxSystemLayoutFixtureTest {

    private static LogicalCpu cpu(int cpu, int socket, int die, int core) {
        return new LogicalCpu(
                cpu, "linux:package:" + socket, "linux:die:" + die, "linux:core:" + core, CoreKind.UNKNOWN);
    }

    @Test
    void normalizesSparseMultisocketTopology() {
        List<LogicalCpu> values = new ArrayList<>(
                List.of(cpu(16, 1, 0, 0), cpu(2, 0, 0, 0), cpu(10, 0, 1, 0), cpu(0, 0, 0, 0), cpu(8, 0, 1, 0)));
        LinuxSystemLayout layout = new LinuxSystemLayout(() -> new TopologyInput("linux", values, List.of()));

        assertArrayEquals(
                new Integer[] {0, 2, 8, 10, 16}, layout.getCpuInfoMap().keySet().toArray(Integer[]::new));
        assertEquals(3, layout.getCoreInfoMap().size());
        assertEquals(2, layout.getSocketInfoMap().size());
        assertNotEquals(
                layout.getCpuInfoMap().get(0).core(),
                layout.getCpuInfoMap().get(8).core());
        assertNotEquals(
                layout.getCpuInfoMap().get(8).core(),
                layout.getCpuInfoMap().get(16).core());
        assertNull(layout.getCpuInfoMap().get(1));
        for (int cpu : List.of(0, 2, 8, 10, 16)) {
            assertNotNull(layout.getCacheLayout().get(cpu));
        }
    }

    @Test
    void scansSysfsFilesystemAndParsesSparseTopology(@TempDir Path tempDir) throws IOException {
        Path cpu0 = Files.createDirectories(tempDir.resolve("cpu0/topology"));
        Files.writeString(cpu0.resolve("physical_package_id"), "0");
        Files.writeString(cpu0.resolve("core_id"), "0");
        Files.writeString(cpu0.resolve("die_id"), "0");

        Path cache0_0 = Files.createDirectories(tempDir.resolve("cpu0/cache/index0"));
        Files.writeString(cache0_0.resolve("type"), "Data");
        Files.writeString(cache0_0.resolve("level"), "1");
        Files.writeString(cache0_0.resolve("size"), "32K");
        Files.writeString(cache0_0.resolve("coherency_line_size"), "64");
        Files.writeString(cache0_0.resolve("shared_cpu_map"), "1");

        Path cpu2 = Files.createDirectories(tempDir.resolve("cpu2/topology"));
        Files.writeString(cpu2.resolve("physical_package_id"), "0");
        Files.writeString(cpu2.resolve("core_id"), "1");

        Path cpu8 = Files.createDirectories(tempDir.resolve("cpu8/topology"));
        Files.writeString(cpu8.resolve("physical_package_id"), "1");
        Files.writeString(cpu8.resolve("core_id"), "0");

        LinuxSystemLayout layout = new LinuxSystemLayout(tempDir);

        assertArrayEquals(
                new Integer[] {0, 2, 8}, layout.getCpuInfoMap().keySet().toArray(Integer[]::new));
        assertEquals(3, layout.getCoreInfoMap().size());
        assertEquals(2, layout.getSocketInfoMap().size());
        assertNull(layout.getCpuInfoMap().get(1));

        // Package 0 core 0 vs Package 1 core 0 must produce distinct CoreInfo instances
        assertNotEquals(
                layout.getCpuInfoMap().get(0).core(),
                layout.getCpuInfoMap().get(8).core());
    }

    @Test
    void classifiesHybridPerformanceAndEfficiencyCoresFromCpufreq(@TempDir Path tempDir) throws IOException {
        Path cpu0 = Files.createDirectories(tempDir.resolve("cpu0/topology"));
        Files.writeString(cpu0.resolve("physical_package_id"), "0");
        Files.writeString(cpu0.resolve("core_id"), "0");
        Files.createDirectories(tempDir.resolve("cpu0/cpufreq"));
        Files.writeString(tempDir.resolve("cpu0/cpufreq/cpuinfo_max_freq"), "5000000");

        Path cpu1 = Files.createDirectories(tempDir.resolve("cpu1/topology"));
        Files.writeString(cpu1.resolve("physical_package_id"), "0");
        Files.writeString(cpu1.resolve("core_id"), "1");
        Files.createDirectories(tempDir.resolve("cpu1/cpufreq"));
        Files.writeString(tempDir.resolve("cpu1/cpufreq/cpuinfo_max_freq"), "3000000");

        LinuxSystemLayout layout = new LinuxSystemLayout(tempDir);

        int core0 = layout.getCpuInfoMap().get(0).core();
        int core1 = layout.getCpuInfoMap().get(1).core();

        assertTrue(layout.getCoreInfoMap().get(core0).pCore());
        assertTrue(!layout.getCoreInfoMap().get(core1).pCore());
    }

    @Test
    void classifiesHybridCoresFromCacheCapacityScoresWhenFreqUnavailable(@TempDir Path tempDir) throws IOException {
        Path cpu0 = Files.createDirectories(tempDir.resolve("cpu0/topology"));
        Files.writeString(cpu0.resolve("physical_package_id"), "0");
        Files.writeString(cpu0.resolve("core_id"), "0");

        Path c0_l1 = Files.createDirectories(tempDir.resolve("cpu0/cache/index0"));
        Files.writeString(c0_l1.resolve("type"), "Data");
        Files.writeString(c0_l1.resolve("level"), "1");
        Files.writeString(c0_l1.resolve("size"), "64K");
        Files.writeString(c0_l1.resolve("coherency_line_size"), "64");
        Files.writeString(c0_l1.resolve("shared_cpu_map"), "1");

        Path c0_l2 = Files.createDirectories(tempDir.resolve("cpu0/cache/index1"));
        Files.writeString(c0_l2.resolve("type"), "Unified");
        Files.writeString(c0_l2.resolve("level"), "2");
        Files.writeString(c0_l2.resolve("size"), "2M");
        Files.writeString(c0_l2.resolve("coherency_line_size"), "64");
        Files.writeString(c0_l2.resolve("shared_cpu_map"), "1");

        Path cpu1 = Files.createDirectories(tempDir.resolve("cpu1/topology"));
        Files.writeString(cpu1.resolve("physical_package_id"), "0");
        Files.writeString(cpu1.resolve("core_id"), "1");

        Path c1_l1 = Files.createDirectories(tempDir.resolve("cpu1/cache/index0"));
        Files.writeString(c1_l1.resolve("type"), "Data");
        Files.writeString(c1_l1.resolve("level"), "1");
        Files.writeString(c1_l1.resolve("size"), "32K");
        Files.writeString(c1_l1.resolve("coherency_line_size"), "64");
        Files.writeString(c1_l1.resolve("shared_cpu_map"), "2");

        Path c1_l2 = Files.createDirectories(tempDir.resolve("cpu1/cache/index1"));
        Files.writeString(c1_l2.resolve("type"), "Unified");
        Files.writeString(c1_l2.resolve("level"), "2");
        Files.writeString(c1_l2.resolve("size"), "512K");
        Files.writeString(c1_l2.resolve("coherency_line_size"), "64");
        Files.writeString(c1_l2.resolve("shared_cpu_map"), "2");

        LinuxSystemLayout layout = new LinuxSystemLayout(tempDir);

        int core0 = layout.getCpuInfoMap().get(0).core();
        int core1 = layout.getCpuInfoMap().get(1).core();

        assertTrue(layout.getCoreInfoMap().get(core0).pCore());
        assertTrue(!layout.getCoreInfoMap().get(core1).pCore());
    }

    @Test
    void fallsBackToUnknownWhenScoresAreHomogeneousOrIncomplete(@TempDir Path tempDir) throws IOException {
        Path cpu0 = Files.createDirectories(tempDir.resolve("cpu0/topology"));
        Files.writeString(cpu0.resolve("physical_package_id"), "0");
        Files.writeString(cpu0.resolve("core_id"), "0");
        Files.createDirectories(tempDir.resolve("cpu0/cpufreq"));
        Files.writeString(tempDir.resolve("cpu0/cpufreq/cpuinfo_max_freq"), "3000000");

        Path cpu1 = Files.createDirectories(tempDir.resolve("cpu1/topology"));
        Files.writeString(cpu1.resolve("physical_package_id"), "0");
        Files.writeString(cpu1.resolve("core_id"), "1");
        Files.createDirectories(tempDir.resolve("cpu1/cpufreq"));
        Files.writeString(tempDir.resolve("cpu1/cpufreq/cpuinfo_max_freq"), "3000000");

        LinuxSystemLayout layout = new LinuxSystemLayout(tempDir);

        int core0 = layout.getCpuInfoMap().get(0).core();
        int core1 = layout.getCpuInfoMap().get(1).core();

        // Homogeneous frequency -> all cores default to performance boolean = true in CoreInfo
        assertTrue(layout.getCoreInfoMap().get(core0).pCore());
        assertTrue(layout.getCoreInfoMap().get(core1).pCore());
    }

    @Test
    void handlesMissingOrUnreadableCpuRootGracefully(@TempDir Path tempDir) {
        Path nonexistent = tempDir.resolve("nonexistent_cpu_root");
        LinuxSystemLayout layout = new LinuxSystemLayout(nonexistent);

        assertNotNull(layout.getCpuInfoMap());
        assertEquals(
                Runtime.getRuntime().availableProcessors(),
                layout.getCpuInfoMap().size());
        assertNotNull(layout.getCpuInfoMap().get(0));
    }

    private static void writeCpu(Path root, int cpu, String pkg, String core) throws IOException {
        Path topology = Files.createDirectories(root.resolve("cpu" + cpu + "/topology"));
        if (pkg != null) {
            Files.writeString(topology.resolve("physical_package_id"), pkg);
        }
        if (core != null) {
            Files.writeString(topology.resolve("core_id"), core);
        }
    }

    @Test
    void offlineCpuIsExcluded(@TempDir Path tempDir) throws IOException {
        writeCpu(tempDir, 0, "0", "0");
        writeCpu(tempDir, 1, "0", "1");
        Files.createDirectories(tempDir.resolve("cpu2")); // offline: no topology directory
        writeCpu(tempDir, 3, "0", "3");
        Files.writeString(tempDir.resolve("online"), "0-1,3\n");

        LinuxSystemLayout layout = new LinuxSystemLayout(tempDir);

        assertArrayEquals(
                new Integer[] {0, 1, 3}, layout.getCpuInfoMap().keySet().toArray(Integer[]::new));
        assertEquals(3, layout.getCoreInfoMap().size());
        int core0 = layout.getCpuInfoMap().get(0).core();
        assertEquals(1, layout.getCoreInfoMap().get(core0).getCpuSet().cardinality());
        assertNotEquals(core0, layout.getCpuInfoMap().get(1).core());
        assertNotEquals(core0, layout.getCpuInfoMap().get(3).core());
    }

    @Test
    void absentOrMalformedOnlineFileKeepsAllDirectories(@TempDir Path tempDir) throws IOException {
        writeCpu(tempDir, 0, "0", "0");
        writeCpu(tempDir, 1, "0", "1");
        assertEquals(2, new LinuxSystemLayout(tempDir).getCpuInfoMap().size());
        Files.writeString(tempDir.resolve("online"), "3-1");
        assertEquals(2, new LinuxSystemLayout(tempDir).getCpuInfoMap().size());
    }

    @Test
    void missingCoreIdDoesNotMergeCpus(@TempDir Path tempDir) throws IOException {
        writeCpu(tempDir, 0, "0", "0");
        writeCpu(tempDir, 5, "0", null);
        writeCpu(tempDir, 6, "0", null);

        LinuxSystemLayout layout = new LinuxSystemLayout(tempDir);

        assertEquals(3, layout.getCoreInfoMap().size());
        int core0 = layout.getCpuInfoMap().get(0).core();
        int core5 = layout.getCpuInfoMap().get(5).core();
        int core6 = layout.getCpuInfoMap().get(6).core();
        assertNotEquals(core0, core5);
        assertNotEquals(core5, core6);
    }

    @Test
    void missingPackageIdDoesNotMergeIntoPackageZero(@TempDir Path tempDir) throws IOException {
        writeCpu(tempDir, 0, "0", "0");
        writeCpu(tempDir, 1, null, "0");

        LinuxSystemLayout layout = new LinuxSystemLayout(tempDir);

        assertEquals(2, layout.getSocketInfoMap().size());
        assertNotEquals(
                layout.getCpuInfoMap().get(0).socket(),
                layout.getCpuInfoMap().get(1).socket());
        assertNotEquals(
                layout.getCpuInfoMap().get(0).core(),
                layout.getCpuInfoMap().get(1).core());
    }

    @Test
    void parseCpuListHandlesRangesSinglesAndWhitespace() {
        assertEquals(bits(0, 1, 2, 3, 6, 8, 9), LinuxSystemLayout.parseCpuList("0-3,6,8-9"));
        assertEquals(bits(4), LinuxSystemLayout.parseCpuList(" 4\n"));
        assertEquals(bits(0, 1, 5), LinuxSystemLayout.parseCpuList(" 0 - 1 , 5 \n"));
    }

    @Test
    void parseCpuListRejectsMalformedReversedAndEmptyInput() {
        for (String bad : new String[] {
            "", "  \n", "3-1", "a", "1-b", "1,,2", "1,", ",1", "-1", "1-", "1-2-3", "0x3", "+1", "99999999999"
        }) {
            assertNull(LinuxSystemLayout.parseCpuList(bad), bad);
        }
        assertNull(LinuxSystemLayout.parseCpuList(null));
    }

    private static java.util.BitSet bits(int... values) {
        java.util.BitSet set = new java.util.BitSet();
        for (int value : values) {
            set.set(value);
        }
        return set;
    }
}
