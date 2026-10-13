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
}
