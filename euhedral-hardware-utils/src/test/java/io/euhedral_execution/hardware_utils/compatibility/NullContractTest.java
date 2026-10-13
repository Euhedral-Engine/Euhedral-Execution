package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.topology.SystemInfo;
import io.euhedral_execution.hardware_utils.topology.TopologyMapper;
import java.lang.reflect.AnnotatedParameterizedType;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class NullContractTest {

    @Test
    void holeReturningLookupsAreNullable() throws Exception {
        for (String name : List.of("getSocketInfo", "getCoreInfo", "getCpuInfo", "getCacheLayout")) {
            var method = SystemInfo.class.getMethod(name, int.class);
            assertTrue(
                    method.getAnnotatedReturnType().isAnnotationPresent(Nullable.class),
                    "SystemInfo." + name + " returns null for holes and must be @Nullable");
        }
    }

    @Test
    void otherNullReturningMethodsAreNullable() throws Exception {
        assertTrue(SystemInfo.class
                .getMethod("getSystemSnapshot")
                .getAnnotatedReturnType()
                .isAnnotationPresent(Nullable.class));
        assertTrue(TopologyMapper.class
                .getMethod("getEffectiveSocketTopology", int.class)
                .getAnnotatedReturnType()
                .isAnnotationPresent(Nullable.class));
        assertTrue(io.euhedral_execution.hardware_utils.topology.internal.TopologyBootstrap.class
                .getMethod("resources", org.slf4j.Logger.class)
                .getAnnotatedReturnType()
                .isAnnotationPresent(Nullable.class));
    }

    @Test
    void snapshotterIsNullable() throws Exception {
        // Reflect on the declaration only; reading the field would initialize SystemInfo.
        assertTrue(SystemInfo.class.getField("SNAPSHOTTER").getAnnotatedType().isAnnotationPresent(Nullable.class));
    }

    @Test
    void effectiveSocketListElementsAreNullable() {
        var component = Arrays.stream(TopologyMapper.EffectiveSystemTopology.class.getRecordComponents())
                .filter(c -> c.getName().equals("socketTopologies"))
                .findFirst()
                .orElseThrow();
        var listType = (AnnotatedParameterizedType) component.getAnnotatedType();
        assertTrue(
                listType.getAnnotatedActualTypeArguments()[0].isAnnotationPresent(Nullable.class),
                "inactive sockets are null holes; the element type must be @Nullable");
    }

    @Test
    void coreToCpuListElementsAreNullable() {
        var component = Arrays.stream(TopologyMapper.EffectiveSocketTopology.class.getRecordComponents())
                .filter(c -> c.getName().equals("effectiveCoreToCpu"))
                .findFirst()
                .orElseThrow();
        var listType = (AnnotatedParameterizedType) component.getAnnotatedType();
        assertTrue(
                listType.getAnnotatedActualTypeArguments()[0].isAnnotationPresent(Nullable.class),
                "cores without effective CPUs are null holes; the element type must be @Nullable");
    }

    @Test
    void packagesAreNullMarked() {
        for (Class<?> anchor : List.of(
                SystemInfo.class,
                io.euhedral_execution.hardware_utils.topology.internal.MaskCodec.class,
                io.euhedral_execution.hardware_utils.util.UnmodifiableBitSet.class,
                io.euhedral_execution.hardware_utils.util.internal.Constants.class)) {
            assertTrue(
                    anchor.getPackage().isAnnotationPresent(NullMarked.class),
                    anchor.getPackageName() + " must be @NullMarked");
        }
    }

    private static boolean nullable(java.lang.reflect.AnnotatedType type) {
        return type.isAnnotationPresent(Nullable.class);
    }

    private static boolean nullableReturn(Class<?> owner, String name, Class<?>... params) throws Exception {
        return nullable(owner.getDeclaredMethod(name, params).getAnnotatedReturnType());
    }

    private static boolean nullableParam(Class<?> owner, String name, int index, Class<?>... params) throws Exception {
        return nullable(owner.getDeclaredMethod(name, params).getAnnotatedParameterTypes()[index]);
    }

    private static boolean nullableField(Class<?> owner, String name) throws Exception {
        return nullable(owner.getDeclaredField(name).getAnnotatedType());
    }

    private static void assertMarked(Class<?>... anchors) {
        for (Class<?> anchor : anchors) {
            assertTrue(
                    anchor.getPackage().isAnnotationPresent(NullMarked.class),
                    anchor.getPackageName() + " must be @NullMarked");
        }
    }

    @Test
    void affinityNullContracts() throws Exception {
        var provider = io.euhedral_execution.hardware_utils.affinity.AffinityProvider.class;
        assertTrue(nullableReturn(provider, "captureAffinity"), "captureAffinity returns null when unavailable");
        var tools = io.euhedral_execution.hardware_utils.affinity.ThreadTools.class;
        assertTrue(nullableReturn(tools, "selectProvider"), "selectProvider returns null on unsupported OS");
        var masks = io.euhedral_execution.hardware_utils.affinity.internal.AffinityMasks.class;
        assertTrue(nullableReturn(masks, "canonical", long[].class, int.class, java.util.BitSet.class));
        assertTrue(nullableParam(masks, "canonical", 0, long[].class, int.class, java.util.BitSet.class));
        var executor = io.euhedral_execution.hardware_utils.affinity.PinnedThreadExecutor.class;
        assertTrue(nullableReturn(executor, "get", long.class), "get returns null when no running executor");
        var controller = io.euhedral_execution.hardware_utils.affinity.internal.AffinityController.class;
        var ctor = controller.getConstructors()[0];
        assertTrue(nullable(ctor.getAnnotatedParameterTypes()[0]), "provider is null when unsupported");
        assertTrue(nullable(ctor.getAnnotatedParameterTypes()[3]), "logger is null in deterministic tests");
        assertTrue(nullableField(controller, "provider"));
        assertTrue(nullableField(controller, "logger"));
        assertTrue(nullableParam(controller, "canonical", 0, long[].class));
        assertTrue(nullableReturn(controller, "canonical", long[].class));
        assertTrue(nullableParam(controller, "setAffinity", 0, long[].class));
        assertTrue(nullableParam(controller, "setAffinity", 0, int[].class));
        assertTrue(nullableParam(controller, "setAffinity", 0, java.util.BitSet.class));
        assertTrue(nullableParam(controller, "diagnostic", 1, String.class, Throwable.class));
    }

    @Test
    void affinityPackagesAreNullMarked() {
        assertMarked(
                io.euhedral_execution.hardware_utils.affinity.ThreadTools.class,
                io.euhedral_execution.hardware_utils.affinity.internal.AffinityMasks.class);
    }

    private static boolean nullableComponent(Class<?> record, String name) {
        return nullable(Arrays.stream(record.getRecordComponents())
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow()
                .getAnnotatedType());
    }

    private static boolean nullableArrayElement(Class<?> record, String name) {
        var type = Arrays.stream(record.getRecordComponents())
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow()
                .getAnnotatedType();
        return ((java.lang.reflect.AnnotatedArrayType) type)
                .getAnnotatedGenericComponentType()
                .isAnnotationPresent(Nullable.class);
    }

    @Test
    void monitorNullContracts() throws Exception {
        var monitor = io.euhedral_execution.hardware_utils.monitor.SystemSnapshotProvider.class;
        assertTrue(nullableReturn(monitor, "getSnapshot"), "Windows resources can return a null snapshot");
        var adapter = io.euhedral_execution.hardware_utils.monitor.internal.SystemSnapshotCompatibilityAdapter.class;
        assertTrue(nullableReturn(adapter, "getSnapshot"), "adapter forwards the delegate's null snapshot");
        var resourceMonitor = io.euhedral_execution.hardware_utils.monitor.ResourceMonitor.class;
        assertTrue(nullableField(resourceMonitor, "pollingThread"));
        assertTrue(nullableField(resourceMonitor, "lastUtilization"));

        var util = io.euhedral_execution.hardware_utils.monitor.SystemUtilization.class;
        var hardware = util.getDeclaredClasses();
        Class<?> hu = Arrays.stream(hardware)
                .filter(c -> c.getSimpleName().equals("HardwareUtilization"))
                .findFirst()
                .orElseThrow();
        Class<?> socket = Arrays.stream(hardware)
                .filter(c -> c.getSimpleName().equals("SocketSnapshot"))
                .findFirst()
                .orElseThrow();
        Class<?> core = Arrays.stream(hardware)
                .filter(c -> c.getSimpleName().equals("CoreSnapshot"))
                .findFirst()
                .orElseThrow();
        assertTrue(nullableComponent(hu, "snapshot"), "snapshot is optional");
        var getSocket = hu.getMethod("getSocketSnapshot", int.class, java.util.List.class, double.class);
        assertTrue(nullable(getSocket.getAnnotatedReturnType()), "returns null for empty sockets");
        assertTrue(nullable(getSocket.getAnnotatedParameterTypes()[1]), "null list returns null");
        assertTrue(
                ((AnnotatedParameterizedType) getSocket.getAnnotatedParameterTypes()[1])
                        .getAnnotatedActualTypeArguments()[0].isAnnotationPresent(Nullable.class),
                "cores without CPUs are null holes");
        assertTrue(nullableArrayElement(socket, "coreSnapshots"), "inactive cores are null holes");
        assertTrue(nullableArrayElement(core, "cpuSnapshots"), "inactive CPUs are null holes");
        assertTrue(nullable(socket.getMethod("equals", Object.class).getAnnotatedParameterTypes()[0]));
        assertTrue(nullable(core.getMethod("equals", Object.class).getAnnotatedParameterTypes()[0]));
    }

    @Test
    void samplingNullContracts() throws Exception {
        String samples = "io.euhedral_execution.hardware_utils.monitor.sampling.";
        var fast = Class.forName(samples + "samples.FastHardwareSample");
        for (String name : List.of(
                "quotaCapacityCpus",
                "quotaPeriodNs",
                "productiveCpuNs",
                "scopeQuotaThrottledNs",
                "scopeSchedulerWaitNs",
                "scopePsiStallNs",
                "scopeReportedSchedulerStallRatio")) {
            assertTrue(nullableComponent(fast, name), "FastHardwareSample." + name + " is tolerated null");
        }
        for (String name : List.of(
                "schedulerWait",
                "psiStall",
                "reportedSchedulerStallRatio",
                "quotaThrottle",
                "steal",
                "externalContentionRatio",
                "runnablePerCapacity")) {
            assertTrue(nullableComponent(Class.forName(samples + "signals.CpuFastSignals"), name), name);
        }
        for (String name : List.of(
                "hardLimitBytes",
                "highLimitBytes",
                "usageBytes",
                "inactiveFileBytes",
                "cumulativeReclaimBytes",
                "memoryStallNs")) {
            assertTrue(nullableComponent(Class.forName(samples + "signals.MemoryFastSignals"), name), name);
        }
        for (String name : List.of(
                "productiveBytes", "stallNs", "operationLatencyNs", "completedOperations", "maximumQueueDepth")) {
            assertTrue(nullableComponent(Class.forName(samples + "signals.IoFastSignals"), name), name);
        }
        var internal = "io.euhedral_execution.hardware_utils.monitor.internal.";
        var engine = Class.forName(internal + "SampleStateEngine");
        var fastSample = fast;
        assertTrue(nullableReturn(engine, "processFast", long.class, fastSample));
        assertTrue(nullableParam(engine, "processFast", 1, long.class, fastSample));
        assertTrue(nullableParam(
                engine, "processSlow", 1, long.class, Class.forName(samples + "samples.SlowHardwareSample")));
        assertTrue(nullableField(engine, "lastMembership"));
        var cache = Class.forName(internal + "SlowSampleCache");
        assertTrue(nullableReturn(cache, "resolve", long.class));
        assertTrue(nullableField(cache, "lastSample"));
        var constants = Class.forName(internal + "pressure.PressureConstants");
        assertTrue(nullableParam(constants, "thermalLoss", 0, Class.forName(samples + "enums.ThermalSeverity")));
    }

    @Test
    void monitorPackagesAreNullMarked() throws Exception {
        String base = "io.euhedral_execution.hardware_utils.monitor.";
        assertMarked(
                io.euhedral_execution.hardware_utils.monitor.ResourceMonitor.class,
                Class.forName(base + "sampling.DetailedSystemSnapshotProvider"),
                Class.forName(base + "sampling.enums.SignalValidity"),
                Class.forName(base + "sampling.primitives.CounterSignal"),
                Class.forName(base + "sampling.samples.FastHardwareSample"),
                Class.forName(base + "sampling.signals.CpuFastSignals"),
                Class.forName(base + "internal.MonotonicClock"),
                Class.forName(base + "internal.pressure.PressureState"));
    }
}
