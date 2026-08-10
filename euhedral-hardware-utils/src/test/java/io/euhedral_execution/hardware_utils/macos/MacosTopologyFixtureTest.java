package io.euhedral_execution.hardware_utils.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.macos.sysctl.SysctlInt;
import io.euhedral_execution.hardware_utils.macos.sysctl.SysctlLong;
import io.euhedral_execution.hardware_utils.macos.sysctl.SysctlProvider;
import io.euhedral_execution.hardware_utils.macos.sysctl.SysctlString;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class MacosTopologyFixtureTest {

    private static SysctlProvider createMockProvider(Map<String, Object> keys) {
        return key -> Optional.ofNullable(keys.get(key));
    }

    @Test
    void testAppleSiliconHeterogeneousCoreClassification() {
        Map<String, Object> map = new HashMap<>();
        map.put("hw.logicalcpu", 10);
        map.put("hw.physicalcpu", 10);
        map.put("hw.packages", 1);
        map.put("hw.nperflevels", 2);
        map.put("hw.perflevel0.logicalcpu", 6);
        map.put("hw.perflevel0.cpusperl2", 6);
        map.put("hw.perflevel1.logicalcpu", 4);
        map.put("hw.perflevel1.cpusperl2", 4);
        map.put("hw.l1dcachesize", 65536L);
        map.put("hw.l2cachesize", 4194304L);
        map.put("hw.l3cachesize", 0L);
        map.put("hw.cachelinesize", 128);

        MacosSystemLayout layout = new MacosSystemLayout(createMockProvider(map));

        Map<Integer, CpuInfo> cpuMap = layout.getCpuInfoMap();
        assertEquals(10, cpuMap.size());

        Map<Integer, CoreInfo> coreMap = layout.getCoreInfoMap();
        assertEquals(10, coreMap.size());

        // Check E-cores (0..3) vs P-cores (4..9)
        for (int i = 0; i < 4; i++) {
            assertTrue(layout.getModel().eCpuSet().get(i), "CPU " + i + " should be in eCpuSet");
            assertFalse(layout.getModel().pCpuSet().get(i), "CPU " + i + " should not be in pCpuSet");
        }
        for (int i = 4; i < 10; i++) {
            assertTrue(layout.getModel().pCpuSet().get(i), "CPU " + i + " should be in pCpuSet");
            assertFalse(layout.getModel().eCpuSet().get(i), "CPU " + i + " should not be in eCpuSet");
        }
    }

    @Test
    void testAppleSiliconMultipleL2Clusters() {
        Map<String, Object> map = new HashMap<>();
        map.put("hw.logicalcpu", 10);
        map.put("hw.physicalcpu", 10);
        map.put("hw.packages", 1);
        map.put("hw.nperflevels", 2);
        map.put("hw.perflevel0.logicalcpu", 6);
        map.put("hw.perflevel0.cpusperl2", 3);
        map.put("hw.perflevel1.logicalcpu", 4);
        map.put("hw.perflevel1.cpusperl2", 4);
        map.put("hw.l1dcachesize", 65536L);
        map.put("hw.l2cachesize", 4194304L);
        map.put("hw.l3cachesize", 0L);

        MacosSystemLayout layout = new MacosSystemLayout(createMockProvider(map));
        assertNotNull(layout.getCacheLayout());
        assertEquals(10, layout.getCpuInfoMap().size());
    }

    @Test
    void testIntelSmtHyperthreadingDiscovery() {
        Map<String, Object> map = new HashMap<>();
        map.put("hw.logicalcpu", 16);
        map.put("hw.physicalcpu", 8);
        map.put("hw.packages", 1);
        map.put("hw.nperflevels", 1);
        map.put("machdep.cpu.brand_string", "Intel(R) Core(TM) i9-9980HK CPU @ 2.40GHz");
        map.put("hw.l1dcachesize", 32768L);
        map.put("hw.l2cachesize", 262144L);
        map.put("hw.l3cachesize", 16777216L);
        map.put("hw.cachelinesize", 64);

        MacosSystemLayout layout = new MacosSystemLayout(createMockProvider(map));

        assertEquals(16, layout.getCpuInfoMap().size());
        assertEquals(8, layout.getCoreInfoMap().size());

        // Check hyperthreads share core index
        for (int i = 0; i < 16; i++) {
            CpuInfo cpu = layout.getCpuInfoMap().get(i);
            assertEquals(i / 2, cpu.core(), "CPU " + i + " should belong to core " + (i / 2));
            assertTrue(layout.getModel().pCpuSet().get(i));
            assertFalse(layout.getModel().eCpuSet().get(i));
        }
    }

    @Test
    void testHomogeneousModel() {
        Map<String, Object> map = new HashMap<>();
        map.put("hw.logicalcpu", 4);
        map.put("hw.physicalcpu", 4);
        map.put("hw.packages", 1);
        map.put("hw.nperflevels", 1);
        map.put("hw.l1dcachesize", 32768L);
        map.put("hw.l2cachesize", 2097152L);

        MacosSystemLayout layout = new MacosSystemLayout(createMockProvider(map));

        assertEquals(4, layout.getCpuInfoMap().size());
        assertEquals(4, layout.getCoreInfoMap().size());
        for (int i = 0; i < 4; i++) {
            assertTrue(layout.getModel().pCpuSet().get(i));
            assertFalse(layout.getModel().eCpuSet().get(i));
        }
    }

    @Test
    void testMissingKeyConservativeFallback() {
        MacosSystemLayout layout = new MacosSystemLayout(createMockProvider(Map.of()));

        assertNotNull(layout.getCpuInfoMap());
        assertNotNull(layout.getCoreInfoMap());
        assertFalse(layout.getCpuInfoMap().isEmpty());
    }

    @Test
    void testTypeSafeSysctlParsers() {
        Map<String, Object> map =
                Map.of("hw.logicalcpu", 8, "hw.memsize", 17179869184L, "machdep.cpu.brand_string", "Apple M1");

        SysctlProvider provider = createMockProvider(map);

        OptionalInt intVal = SysctlInt.query(provider, "hw.logicalcpu");
        assertTrue(intVal.isPresent());
        assertEquals(8, intVal.getAsInt());

        OptionalLong longVal = SysctlLong.query(provider, "hw.memsize");
        assertTrue(longVal.isPresent());
        assertEquals(17179869184L, longVal.getAsLong());

        Optional<String> strVal = SysctlString.query(provider, "machdep.cpu.brand_string");
        assertTrue(strVal.isPresent());
        assertEquals("Apple M1", strVal.get());

        // Test missing keys return empty optionals
        assertFalse(SysctlInt.query(provider, "nonexistent.key").isPresent());
        assertFalse(SysctlLong.query(provider, "nonexistent.key").isPresent());
        assertFalse(SysctlString.query(provider, "nonexistent.key").isPresent());
    }
}
