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
}
