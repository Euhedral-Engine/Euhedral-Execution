package io.euhedral_execution.hardware_utils.internal.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopologyNormalizerTest {

    private static TopologyInput input(int id) {
        return new TopologyInput("fallback", List.of(new LogicalCpu(id, "fallback:package:0",
                "fallback:die:0", "fallback:core:" + String.format("%08x", id),
                CoreKind.UNKNOWN)), List.of());
    }

    private static LogicalCpu cpu(int id, CoreKind kind) {
        return new LogicalCpu(id, "fallback:package:0", "fallback:die:0",
                "fallback:core:00000000", kind);
    }

    private static LogicalCpu fallbackCpu(int id) {
        return new LogicalCpu(id, "fallback:package:0", "fallback:die:0",
                "fallback:core:" + String.format("%08x", id), CoreKind.UNKNOWN);
    }

    @Test
    void acceptsMaximumLogicalIdAndRejectsTheNextId() {
        TopologyNormalizer normalizer = new TopologyNormalizer();
        TopologyModel model = normalizer.normalize(input(TopologyNormalizer.MAX_LOGICAL_CPU_ID));
        assertEquals(1_048_576, model.cpuCount());
        assertThrows(TopologyValidationException.class,
                () -> normalizer.normalize(input(TopologyNormalizer.MAX_LOGICAL_CPU_ID + 1)));
    }

    @Test
    void rejectsDuplicateIdsAndConflictingSiblingKinds() {
        LogicalCpu cpu = cpu(0, CoreKind.PERFORMANCE);
        assertThrows(TopologyValidationException.class, () -> new TopologyNormalizer().normalize(
                new TopologyInput("fallback", List.of(cpu, cpu), List.of())));
        assertThrows(TopologyValidationException.class, () -> new TopologyNormalizer().normalize(
                new TopologyInput("fallback", List.of(cpu(0, CoreKind.PERFORMANCE),
                        cpu(1, CoreKind.EFFICIENCY)), List.of())));
    }

    @Test
    void rejectsIndexBudgetBeforeBuildingPerCoreMasks() {
        List<LogicalCpu> cpus = new ArrayList<>();
        for (int core = 0; core < 18; core++) {
            int id = 1_000_000 + core;
            cpus.add(new LogicalCpu(id, "fallback:package:0", "fallback:die:0",
                    "fallback:core:" + String.format("%08x", core), CoreKind.UNKNOWN));
        }
        TopologyValidationException failure = assertThrows(TopologyValidationException.class,
                () -> new TopologyNormalizer().normalize(
                        new TopologyInput("fallback", cpus, List.of())));
        assertEquals("core-index-sum", failure.category());
    }

    @Test
    void rejectsActiveCountAboveBoundFromCompactInput() {
        List<LogicalCpu> cpus = Collections.nCopies(TopologyNormalizer.MAX_ACTIVE_CPUS + 1,
                fallbackCpu(0));
        TopologyValidationException failure = assertThrows(TopologyValidationException.class,
                () -> new TopologyNormalizer().normalize(
                        new TopologyInput("fallback", cpus, List.of())));
        assertEquals("active-count", failure.category());
    }

    @Test
    void shuffledInputProducesIdenticalProjection() {
        List<LogicalCpu> cpus = new ArrayList<>(List.of(
                fallbackCpu(8), fallbackCpu(0), fallbackCpu(2)));
        TopologyModel first = new TopologyNormalizer().normalize(
                new TopologyInput("fallback", cpus, List.of()));
        Collections.reverse(cpus);
        TopologyModel second = new TopologyNormalizer().normalize(
                new TopologyInput("fallback", cpus, List.of()));
        assertEquals(first.cpuInfo(), second.cpuInfo());
        assertEquals(first.coreInfo(), second.coreInfo());
        assertEquals(first.socketInfo(), second.socketInfo());
        assertEquals(first.cacheLayout(), second.cacheLayout());
    }
}
