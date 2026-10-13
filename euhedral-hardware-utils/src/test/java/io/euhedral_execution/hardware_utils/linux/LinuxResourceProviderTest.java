package io.euhedral_execution.hardware_utils.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.monitor.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.samples.FastHardwareSample;
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
    void oversizedFileIsBounded(@TempDir Path tempDir) throws IOException {
        // Reverses the old testLargeFileBoundedRead, which pinned "return everything".
        Path largeFile = tempDir.resolve("huge.txt");
        byte[] chunk = new byte[1 << 20];
        java.util.Arrays.fill(chunk, (byte) 'a');
        try (var out = Files.newOutputStream(largeFile)) {
            for (int i = 0; i < 4; i++) {
                out.write(chunk);
            }
        }
        Path exact = tempDir.resolve("exact.txt");
        Files.write(exact, chunk);
        Path medium = tempDir.resolve("medium.txt");
        StringBuilder sb = new StringBuilder(100_000);
        for (int i = 0; i < 2000; i++) {
            sb.append("cpu").append(i).append(" 100 200 300 400 500 600 700 800 900 1000\n");
        }
        Files.writeString(medium, sb.toString());

        LinuxPaths paths = new LinuxPaths(tempDir.resolve("mountinfo"), tempDir.resolve("cgroup"), tempDir);
        try (LinuxResourceProvider provider = new LinuxResourceProvider(paths)) {
            assertNull(provider.readFileBounded(largeFile));
            assertNotNull(provider.readFileBounded(exact));
            assertEquals(sb.toString(), provider.readFileBounded(medium));
            assertNull(provider.readFileBounded(tempDir.resolve("missing")));
        }
    }

    private static Path v2Fixture(Path tempDir) throws IOException {
        Path mountinfo = tempDir.resolve("mountinfo");
        Path cgroup = tempDir.resolve("cgroup");
        Path cgroupRoot = tempDir.resolve("sys_cgroup");
        Files.createDirectories(cgroupRoot);
        Files.writeString(mountinfo, "40 28 0:34 / /sys/fs/cgroup rw,nosuid - cgroup2 cgroup2 rw\n");
        Files.writeString(cgroup, "0::/\n");
        Files.writeString(cgroupRoot.resolve("cpuset.cpus.effective"), "0\n");
        return cgroupRoot;
    }

    private static LinuxPaths v2Paths(Path tempDir) {
        return new LinuxPaths(tempDir.resolve("mountinfo"), tempDir.resolve("cgroup"), tempDir.resolve("sys_cgroup"));
    }

    @Test
    void unreadableCpuStatIsTransientFailure(@TempDir Path tempDir) throws IOException {
        Path root = v2Fixture(tempDir);
        Path cpuStat = root.resolve("cpu.stat");
        Files.writeString(cpuStat, "usage_usec 1000\nthrottled_usec 10\n");
        try (LinuxResourceProvider provider = new LinuxResourceProvider(v2Paths(tempDir))) {
            FastHardwareSample first = provider.sampleFast(1L);
            assertEquals(SignalValidity.VALID, first.productiveCpuNs().validity());
            assertEquals(1_000_000L, first.productiveCpuNs().value());
            assertEquals(10_000L, first.scopeQuotaThrottledNs().value());

            // A directory in place of the file: it exists but every read fails. The "deleted file" case
            // is masked by LinuxPaths.resolveV2Path falling back to the host's real cgroup root
            // (which Phase 4 fixes), so it cannot be asserted hermetically here.
            Files.delete(cpuStat);
            Files.createDirectory(cpuStat);
            FastHardwareSample second = provider.sampleFast(2L);
            assertEquals(
                    SignalValidity.TRANSIENT_FAILURE, second.productiveCpuNs().validity());
            assertEquals(
                    SignalValidity.TRANSIENT_FAILURE,
                    second.scopeQuotaThrottledNs().validity());
        }
    }

    @Test
    void missingThrottleKeyIsUnsupportedAcrossTicks(@TempDir Path tempDir) throws IOException {
        Path root = v2Fixture(tempDir);
        Files.writeString(root.resolve("cpu.stat"), "usage_usec 1000\n");
        try (LinuxResourceProvider provider = new LinuxResourceProvider(v2Paths(tempDir))) {
            for (long t = 1; t <= 4; t++) {
                FastHardwareSample s = provider.sampleFast(t);
                assertEquals(SignalValidity.VALID, s.productiveCpuNs().validity());
                assertEquals(
                        SignalValidity.UNSUPPORTED, s.scopeQuotaThrottledNs().validity());
            }
            // Once seen, a vanished key is a failure, and a malformed one is too.
            Files.writeString(root.resolve("cpu.stat"), "usage_usec 1000\nthrottled_usec 5\n");
            assertEquals(
                    SignalValidity.VALID,
                    provider.sampleFast(5L).scopeQuotaThrottledNs().validity());
            Files.writeString(root.resolve("cpu.stat"), "usage_usec 1000\n");
            assertEquals(
                    SignalValidity.TRANSIENT_FAILURE,
                    provider.sampleFast(6L).scopeQuotaThrottledNs().validity());
            Files.writeString(root.resolve("cpu.stat"), "usage_usec 1000\nthrottled_usec junk\n");
            assertEquals(
                    SignalValidity.TRANSIENT_FAILURE,
                    provider.sampleFast(7L).scopeQuotaThrottledNs().validity());
        }
    }

    @Test
    void ioPressureFailureDoesNotFallBackToDiskstats(@TempDir Path tempDir) throws IOException {
        Path root = v2Fixture(tempDir);
        Path psi = root.resolve("io.pressure");
        Files.writeString(psi, "some avg10=0.00 avg60=0.00 avg300=0.00 total=7\n");
        try (LinuxResourceProvider provider = new LinuxResourceProvider(v2Paths(tempDir))) {
            var ok = provider.sampleFast(1L).ioSignals().completedOperations();
            assertEquals(SignalValidity.VALID, ok.validity());
            assertEquals(7000L, ok.value());

            Files.writeString(psi, "some avg10=0.00 total=garbage\n");
            var bad = provider.sampleFast(2L).ioSignals().completedOperations();
            assertEquals(SignalValidity.TRANSIENT_FAILURE, bad.validity());
            assertEquals(0L, bad.value());
        }
    }

    @Test
    void ioPressureAppearingAfterNoSourceAtFirstSightIsChosen(@TempDir Path tempDir) throws IOException {
        Path root = v2Fixture(tempDir);
        Path psi = root.resolve("io.pressure");
        Path diskstats = tempDir.resolve("diskstats");
        try (LinuxResourceProvider provider = new LinuxResourceProvider(v2Paths(tempDir), diskstats, psi)) {
            // Neither source exists: nothing is chosen yet.
            provider.sampleFast(1L);
            provider.sampleFast(2L);

            Files.writeString(psi, "some avg10=0.00 avg60=0.00 avg300=0.00 total=9\n");
            var chosen = provider.sampleFast(3L).ioSignals().completedOperations();
            assertEquals(SignalValidity.VALID, chosen.validity());
            assertEquals(9000L, chosen.value());
        }
    }

    @Test
    void malformedCpuStatIsTransientFailureAndNeverThrows(@TempDir Path tempDir) throws IOException {
        Path root = v2Fixture(tempDir);
        Files.writeString(root.resolve("cpu.stat"), "usage_usec notanumber\n");
        try (LinuxResourceProvider provider = new LinuxResourceProvider(v2Paths(tempDir))) {
            FastHardwareSample s = provider.sampleFast(1L);
            assertEquals(SignalValidity.TRANSIENT_FAILURE, s.productiveCpuNs().validity());
        }
    }

    @Test
    void vanishedSourceIsTransientAndNeverSeenSourceIsUnsupported(@TempDir Path tempDir) throws IOException {
        // memory.current does not exist in the host's cgroup root, so the fallback in
        // LinuxPaths.resolveV2Path cannot mask the fixture.
        Path root = v2Fixture(tempDir);
        try (LinuxResourceProvider provider = new LinuxResourceProvider(v2Paths(tempDir))) {
            FastHardwareSample never = provider.sampleFast(1L);
            assertEquals(
                    SignalValidity.UNSUPPORTED,
                    never.memorySignals().usageBytes().validity());

            Files.writeString(root.resolve("memory.current"), "4096\n");
            FastHardwareSample seen = provider.sampleFast(2L);
            assertEquals(SignalValidity.VALID, seen.memorySignals().usageBytes().validity());
            assertEquals(4096L, seen.memorySignals().usageBytes().value());

            Files.delete(root.resolve("memory.current"));
            FastHardwareSample gone = provider.sampleFast(3L);
            assertEquals(
                    SignalValidity.TRANSIENT_FAILURE,
                    gone.memorySignals().usageBytes().validity());
        }
    }

    @Test
    void fractionalQuotaIsPreserved(@TempDir Path tempDir) throws IOException {
        Path root = v2Fixture(tempDir);
        Files.writeString(root.resolve("cpu.max"), "50000 100000\n");
        try (LinuxResourceProvider provider = new LinuxResourceProvider(v2Paths(tempDir))) {
            FastHardwareSample s = provider.sampleFast(1L);
            // Existing long field keeps its truncating semantics.
            assertEquals(0L, s.quotaCapacityCpus().value());
            assertEquals(SignalValidity.VALID, s.quotaCapacityCpus().validity());
            DoubleGaugeSignal exact = provider.quotaCapacityCpusExact();
            assertEquals(SignalValidity.VALID, exact.validity());
            assertEquals(0.5, exact.value(), 1e-12);
            assertEquals(0.5, provider.getSnapshot().quotaCpus(), 1e-12);
        }
    }

    @Test
    void periodComesFromCpuMax(@TempDir Path tempDir) throws IOException {
        Path root = v2Fixture(tempDir);
        Files.writeString(root.resolve("cpu.max"), "50000 200000\n");
        try (LinuxResourceProvider provider = new LinuxResourceProvider(v2Paths(tempDir))) {
            FastHardwareSample s = provider.sampleFast(1L);
            // quotaPeriodNs is documented in nanoseconds; cpu.max is in microseconds.
            assertEquals(200_000_000L, s.quotaPeriodNs().value());
            assertEquals(0.25, provider.quotaCapacityCpusExact().value(), 1e-12);
            // The legacy snapshot keeps its microsecond unit.
            assertEquals(200_000L, provider.getSnapshot().period());
        }
    }

    @Test
    void zeroPeriodIsAFailureNotInfinity(@TempDir Path tempDir) throws IOException {
        Path root = v2Fixture(tempDir);
        Files.writeString(root.resolve("cpu.max"), "50000 0\n");
        try (LinuxResourceProvider provider = new LinuxResourceProvider(v2Paths(tempDir))) {
            FastHardwareSample s = provider.sampleFast(1L);
            assertEquals(SignalValidity.TRANSIENT_FAILURE, s.quotaCapacityCpus().validity());
            assertEquals(SignalValidity.TRANSIENT_FAILURE, s.quotaPeriodNs().validity());
            assertEquals(
                    SignalValidity.TRANSIENT_FAILURE,
                    provider.quotaCapacityCpusExact().validity());
            assertTrue(Double.isFinite(provider.getSnapshot().quotaCpus()));
        }
    }

    @Test
    void cgroupV1ZeroPeriodIsAFailureNotInfinity(@TempDir Path tempDir) throws IOException {
        Path mountinfo = tempDir.resolve("mountinfo");
        Path cgroup = tempDir.resolve("cgroup");
        Path cpuMount = tempDir.resolve("sys_cgroup_cpu");
        Files.createDirectories(cpuMount);
        Files.writeString(
                mountinfo, "40 28 0:34 / " + cpuMount.toAbsolutePath() + " rw,nosuid - cgroup cgroup rw,cpu\n");
        Files.writeString(cgroup, "2:cpu:/\n");
        Files.writeString(cpuMount.resolve("cpu.cfs_quota_us"), "150000\n");
        Files.writeString(cpuMount.resolve("cpu.cfs_period_us"), "0\n");
        LinuxPaths paths = new LinuxPaths(mountinfo, cgroup, tempDir);
        try (LinuxResourceProvider provider = new LinuxResourceProvider(paths)) {
            FastHardwareSample s = provider.sampleFast(1L);
            assertEquals(SignalValidity.TRANSIENT_FAILURE, s.quotaCapacityCpus().validity());
            assertTrue(Double.isFinite(provider.getSnapshot().quotaCpus()));
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
