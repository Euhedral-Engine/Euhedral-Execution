package io.euhedral_execution.hardware_utils.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.euhedral_execution.hardware_utils.windows.win32.GroupAffinity;
import io.euhedral_execution.hardware_utils.windows.win32.ProcessorRelationship;
import io.euhedral_execution.hardware_utils.windows.win32.Relationship;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowsTopologyFixtureTest {

    private static ProcessorRelationship relationship(Relationship relationship,
            GroupAffinity... affinities) {
        return new ProcessorRelationship(relationship, false, true, List.of(affinities));
    }

    private static GroupAffinity affinity(long mask, int group) {
        return new GroupAffinity(mask, (short) group);
    }

    @Test
    void mapsGroupsAndBitSixtyThreeBijectively() {
        ProcessorRelationship packageValue = relationship(Relationship.PROCESSOR_PACKAGE,
                affinity(1, 0), affinity(Long.MIN_VALUE, 0), affinity(1, 1),
                affinity(Long.MIN_VALUE, 1));
        WindowsSystemLayout layout = new WindowsSystemLayout(List.of(
                relationship(Relationship.PROCESSOR_CORE, affinity(Long.MIN_VALUE, 1)),
                relationship(Relationship.PROCESSOR_CORE, affinity(1, 0)), packageValue,
                relationship(Relationship.PROCESSOR_CORE, affinity(1, 1)),
                relationship(Relationship.PROCESSOR_CORE, affinity(Long.MIN_VALUE, 0))));

        assertEquals(List.of(0, 63, 64, 127), List.copyOf(layout.getCpuInfoMap().keySet()));
        for (int cpu : List.of(0, 63, 64, 127)) {
            assertNotNull(layout.getCacheLayout().get(cpu));
        }
    }

    @Test
    void rejectsMissingPackageOwner() {
        assertThrows(IllegalArgumentException.class, () -> new WindowsSystemLayout(List.of(
                relationship(Relationship.PROCESSOR_CORE, affinity(1, 0)))));
    }
}
