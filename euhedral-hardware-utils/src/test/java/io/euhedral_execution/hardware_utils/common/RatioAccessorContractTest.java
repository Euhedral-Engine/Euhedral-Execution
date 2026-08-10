package io.euhedral_execution.hardware_utils.common;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RatioAccessorContractTest {

    private static final Set<String> ALLOWED_RATIOS = Set.of(
            "HardwareUtilization.quotaCpuUsage",
            "HardwareUtilization.cpuThrottleRatio",
            "HardwareUtilization.totalMemoryUtilization",
            "HardwareUtilization.diskIOPressure",
            "HardwareUtilization.pressure",
            "SystemSnapshot.quotaCpus",
            "HardwareUtilization.quotaCpus",
            "CpuSnapshot.quotaCpus",
            "CoreSnapshot.quotaCpus",
            "CpuSnapshot.memoryUtilization",
            "CpuSnapshot.stallRatio",
            "CpuSnapshot.throttleRatio",
            "CpuSnapshot.pressure",
            "CoreSnapshot.memoryUtilization",
            "SocketSnapshot.memoryUtilization");

    private static final Set<String> ALLOWED_RATES = Set.of("HardwareUtilization.diskIOBytesPerSecond");

    @Test
    void testAccessorContract() {
        Class<?>[] classes = {
            SystemUtilization.SystemSnapshot.class,
            SystemUtilization.HardwareUtilization.class,
            SystemUtilization.CpuSnapshot.class,
            SystemUtilization.CoreSnapshot.class,
            SystemUtilization.SocketSnapshot.class
        };

        for (Class<?> clazz : classes) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
                    continue;
                }

                String fullName = clazz.getSimpleName() + "." + m.getName();

                if (m.getReturnType() == double.class) {
                    if (!ALLOWED_RATIOS.contains(fullName) && !ALLOWED_RATES.contains(fullName)) {
                        fail("Unclassified double accessor: " + fullName);
                    }
                } else if (m.getReturnType() == UnmodifiableDoubleArray.class) {
                    // Ratio arrays
                    assertTrue(fullName.equals("SystemSnapshot.pressurePerCpu")
                            || fullName.equals("HardwareUtilization.perQuotaCpuThrottleRatio")
                            || fullName.equals("HardwareUtilization.perQuotaCpuPressure"));
                }
            }
        }
    }
}
