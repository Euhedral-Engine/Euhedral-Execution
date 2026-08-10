package io.euhedral_execution.hardware_utils.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.FastHardwareSample;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxResourceProviderTest {

    @Test
    void testCgroupV2Fixture(@TempDir Path tempDir) throws IOException {
        Path mountinfo = tempDir.resolve("mountinfo");
        Path cgroup = tempDir.resolve("cgroup");
        Path cgroupRoot = tempDir.resolve("sys_cgroup");

        Files.createDirectories(cgroupRoot);
        Files.writeString(mountinfo, "40 28 0:34 / /sys/fs/cgroup rw,nosuid - cgroup2 cgroup2 rw\n");
        Files.writeString(cgroup, "0::/user.slice/user-1000.slice\n");

        Path userDir = cgroupRoot.resolve("user.slice/user-1000.slice");
        Files.createDirectories(userDir);

        Files.writeString(userDir.resolve("cpuset.cpus.effective"), "0-3\n");
        Files.writeString(userDir.resolve("cpu.max"), "200000 100000\n");
        Files.writeString(userDir.resolve("cpu.pressure"), "some avg10=0.00 avg60=0.00 avg300=0.00 total=5000\n");

        LinuxPaths paths = new LinuxPaths(mountinfo, cgroup, cgroupRoot);
        assertEquals(LinuxPaths.CgroupMode.CGROUP_V2, paths.getMode());

        try (LinuxResourceProvider provider = new LinuxResourceProvider(paths)) {
            FastHardwareSample sample = provider.sampleFast(1000L);
            assertNotNull(sample);
            assertEquals(2, sample.quotaCapacityCpus().value());
            assertEquals(4, sample.effectiveCpus().cardinality());

            SystemSnapshot legacySnap = provider.getSnapshot();
            assertEquals(2.0, legacySnap.quotaCpus(), 0.001);
        }
    }

    @Test
    void testCgroupV1Fixture(@TempDir Path tempDir) throws IOException {
        Path mountinfo = tempDir.resolve("mountinfo");
        Path cgroup = tempDir.resolve("cgroup");
        Path cpuMount = tempDir.resolve("sys_cgroup_cpu");
        Path cpusetMount = tempDir.resolve("sys_cgroup_cpuset");
        Files.createDirectories(cpuMount);
        Files.createDirectories(cpusetMount);
        Files.writeString(
                mountinfo,
                "40 28 0:34 / " + cpuMount.toAbsolutePath() + " rw,nosuid - cgroup cgroup rw,cpu\n"
                        + "41 28 0:35 / "
                        + cpusetMount.toAbsolutePath()
                        + " rw,nosuid - cgroup cgroup rw,cpuset\n");
        Files.writeString(cgroup, "2:cpu:/docker/1234\n3:cpuset:/docker/1234\n");

        Path dockerCpuDir = cpuMount.resolve("docker/1234");
        Files.createDirectories(dockerCpuDir);
        Path dockerCpusetDir = cpusetMount.resolve("docker/1234");
        Files.createDirectories(dockerCpusetDir);

        Files.writeString(dockerCpusetDir.resolve("cpuset.cpus"), "0-1\n");
        Files.writeString(dockerCpuDir.resolve("cpu.cfs_quota_us"), "150000\n");
        Files.writeString(dockerCpuDir.resolve("cpu.cfs_period_us"), "100000\n");

        LinuxPaths paths = new LinuxPaths(mountinfo, cgroup, tempDir);
        assertEquals(LinuxPaths.CgroupMode.CGROUP_V1, paths.getMode());

        try (LinuxResourceProvider provider = new LinuxResourceProvider(paths)) {
            FastHardwareSample sample = provider.sampleFast(1000L);
            assertNotNull(sample);
            assertEquals(2, sample.effectiveCpus().cardinality());

            SystemSnapshot legacySnap = provider.getSnapshot();
            assertEquals(1.5, legacySnap.quotaCpus(), 0.001);
        }
    }

    @Test
    void testHybridFixture(@TempDir Path tempDir) throws IOException {
        Path mountinfo = tempDir.resolve("mountinfo");
        Path cgroup = tempDir.resolve("cgroup");
        Path cgroupRoot = tempDir.resolve("sys_cgroup");

        Files.createDirectories(cgroupRoot);
        Files.writeString(
                mountinfo,
                "40 28 0:34 / /sys/fs/cgroup rw,nosuid - cgroup2 cgroup2 rw\n"
                        + "41 28 0:35 / /sys/fs/cgroup/cpu rw,nosuid - cgroup cgroup rw,cpu\n");
        Files.writeString(cgroup, "0::/user.slice\n1:cpu:/user.slice\n");

        LinuxPaths paths = new LinuxPaths(mountinfo, cgroup, cgroupRoot);
        assertEquals(LinuxPaths.CgroupMode.HYBRID, paths.getMode());
    }

    @Test
    void testBareHostFixture(@TempDir Path tempDir) throws IOException {
        Path mountinfo = tempDir.resolve("mountinfo_empty");
        Path cgroup = tempDir.resolve("cgroup_empty");

        Files.writeString(mountinfo, "");
        Files.writeString(cgroup, "");

        LinuxPaths paths = new LinuxPaths(mountinfo, cgroup, tempDir);
        assertEquals(LinuxPaths.CgroupMode.BARE_HOST, paths.getMode());
    }

    @Test
    void testUnlimitedQuotaCalculation(@TempDir Path tempDir) throws IOException {
        Path mountinfo = tempDir.resolve("mountinfo");
        Path cgroup = tempDir.resolve("cgroup");
        Path cgroupRoot = tempDir.resolve("sys_cgroup");

        Files.createDirectories(cgroupRoot);
        Files.writeString(mountinfo, "40 28 0:34 / /sys/fs/cgroup rw,nosuid - cgroup2 cgroup2 rw\n");
        Files.writeString(cgroup, "0::/\n");

        Files.writeString(cgroupRoot.resolve("cpuset.cpus.effective"), "0-3\n");
        Files.writeString(cgroupRoot.resolve("cpu.max"), "max 100000\n");

        LinuxPaths paths = new LinuxPaths(mountinfo, cgroup, cgroupRoot);
        try (LinuxResourceProvider provider = new LinuxResourceProvider(paths)) {
            FastHardwareSample sample = provider.sampleFast(1000L);
            // Unlimited quota must equal effectiveCpus cardinality (4)
            assertEquals(4, sample.quotaCapacityCpus().value());
            SystemSnapshot legacySnap = provider.getSnapshot();
            assertEquals(4.0, legacySnap.quotaCpus(), 0.001);
        }
    }

    @Test
    void testBlockDeviceFilter() {
        assertTrue(LinuxResourceProvider.isFilteredBlockDevice(8, "sda"));
        assertTrue(LinuxResourceProvider.isFilteredBlockDevice(259, "nvme0n1"));
        assertTrue(LinuxResourceProvider.isFilteredBlockDevice(254, "vda"));

        assertFalse(LinuxResourceProvider.isFilteredBlockDevice(7, "loop0"));
        assertFalse(LinuxResourceProvider.isFilteredBlockDevice(1, "ram0"));
        assertFalse(LinuxResourceProvider.isFilteredBlockDevice(1, "zram0"));
        assertFalse(LinuxResourceProvider.isFilteredBlockDevice(11, "sr0"));
    }

    @Test
    void testHostActivityIsolation(@TempDir Path tempDir) throws IOException {
        Path mountinfo = tempDir.resolve("mountinfo");
        Path cgroup = tempDir.resolve("cgroup");
        Path cgroupRoot = tempDir.resolve("sys_cgroup");

        Files.createDirectories(cgroupRoot);
        Files.writeString(mountinfo, "40 28 0:34 / /sys/fs/cgroup rw,nosuid - cgroup2 cgroup2 rw\n");
        Files.writeString(cgroup, "0::/\n");

        Files.writeString(cgroupRoot.resolve("cpuset.cpus.effective"), "1\n"); // Constrained to CPU 1
        Files.writeString(cgroupRoot.resolve("cpu.pressure"), "some avg10=0.00 avg60=0.00 avg300=0.00 total=10000\n");

        LinuxPaths paths = new LinuxPaths(mountinfo, cgroup, cgroupRoot);
        try (LinuxResourceProvider provider = new LinuxResourceProvider(paths)) {
            FastHardwareSample sample = provider.sampleFast(1000L);
            var cpuSignals = sample.cpuSignals();
            // Aggregate pressure propagation should apply stall to CPU 1 without interference from CPU 0
            assertTrue(cpuSignals.length > 1);
            assertEquals(0L, cpuSignals[0].schedulerWait().value()); // CPU 0 is not in effective set
            assertEquals(10000000L, cpuSignals[1].schedulerWait().value()); // 10000 us -> 10000000 ns on CPU 1
        }
    }

    @Test
    void testLargeFileBoundedRead(@TempDir Path tempDir) throws IOException {
        Path largeFile = tempDir.resolve("large_stat.txt");
        StringBuilder sb = new StringBuilder(100_000);
        for (int i = 0; i < 2000; i++) {
            sb.append("cpu").append(i).append(" 100 200 300 400 500 600 700 800 900 1000\n");
        }
        String content = sb.toString();
        assertTrue(content.length() > 65536);
        Files.writeString(largeFile, content);

        LinuxPaths paths = new LinuxPaths(tempDir.resolve("mountinfo"), tempDir.resolve("cgroup"), tempDir);
        try (LinuxResourceProvider provider = new LinuxResourceProvider(paths)) {
            String readBack = provider.readFileBounded(largeFile);
            assertNotNull(readBack);
            assertEquals(content.length(), readBack.length());
            assertEquals(content, readBack);
        }
    }

    @Test
    void testConcurrentAccess(@TempDir Path tempDir) throws Exception {
        Path mountinfo = tempDir.resolve("mountinfo");
        Path cgroup = tempDir.resolve("cgroup");
        Path cgroupRoot = tempDir.resolve("sys_cgroup");

        Files.createDirectories(cgroupRoot);
        Files.writeString(mountinfo, "40 28 0:34 / /sys/fs/cgroup rw,nosuid - cgroup2 cgroup2 rw\n");
        Files.writeString(cgroup, "0::/\n");

        Files.writeString(cgroupRoot.resolve("cpuset.cpus.effective"), "0-3\n");
        Files.writeString(cgroupRoot.resolve("cpu.max"), "200000 100000\n");

        LinuxPaths paths = new LinuxPaths(mountinfo, cgroup, cgroupRoot);
        try (LinuxResourceProvider provider = new LinuxResourceProvider(paths)) {
            int numThreads = 8;
            int iterations = 100;
            java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newFixedThreadPool(numThreads);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                futures.add(executor.submit(() -> {
                    latch.await();
                    for (int j = 0; j < iterations; j++) {
                        if (threadId % 3 == 0) {
                            assertNotNull(provider.sampleFast(System.nanoTime()));
                        } else if (threadId % 3 == 1) {
                            assertNotNull(provider.sampleSlow(System.nanoTime()));
                        } else {
                            assertNotNull(provider.getSnapshot());
                        }
                    }
                    return null;
                }));
            }

            latch.countDown();
            for (var future : futures) {
                future.get();
            }
            executor.shutdown();
        }
    }
}
