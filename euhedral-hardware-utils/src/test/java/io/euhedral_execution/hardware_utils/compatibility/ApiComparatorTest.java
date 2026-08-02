package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.compatibility.ApiSurface.Entry;
import io.euhedral_execution.hardware_utils.compatibility.helpers.ApiSurfaceComparator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiComparatorTest {

    @Test
    void rejectsChangedDescriptorsAndRecordComponentOrder() {
        ApiSurface baseline = new ApiSurface(List.of(
                new Entry("module", "module", "name=example"),
                new Entry("method", "Example#value()I", "access=public"),
                new Entry("record", "Example#000000", "name=left;descriptor=I;signature=-"),
                new Entry("record", "Example#000001", "name=right;descriptor=J;signature=-")));

        ApiSurface changedDescriptor = new ApiSurface(List.of(
                new Entry("module", "module", "name=example"),
                new Entry("method", "Example#value()J", "access=public"),
                new Entry("record", "Example#000000", "name=left;descriptor=I;signature=-"),
                new Entry("record", "Example#000001", "name=right;descriptor=J;signature=-")));
        CompatibilityReport descriptorReport = ApiSurfaceComparator.compare(baseline,
                changedDescriptor);
        assertFalse(descriptorReport.passes());
        assertTrue(descriptorReport.render().contains("REMOVED\tmethod\\tExample#value()I"));
        assertTrue(descriptorReport.render().contains("ADDED\tmethod\\tExample#value()J"));

        List<Entry> reordered = new ArrayList<>(baseline.entries().values());
        reordered.removeIf(entry -> entry.kind().equals("record"));
        reordered.add(new Entry("record", "Example#000000",
                "name=right;descriptor=J;signature=-"));
        reordered.add(new Entry("record", "Example#000001",
                "name=left;descriptor=I;signature=-"));
        CompatibilityReport recordReport = ApiSurfaceComparator.compare(baseline,
                new ApiSurface(reordered));
        assertFalse(recordReport.passes());
        assertTrue(recordReport.changed().stream().anyMatch(
                difference -> difference.key().equals("record\tExample#000000")));

        ApiSurface changedModule = new ApiSurface(List.of(
                new Entry("module", "module", "name=changed"),
                new Entry("method", "Example#value()I", "access=public"),
                new Entry("record", "Example#000000", "name=left;descriptor=I;signature=-"),
                new Entry("record", "Example#000001", "name=right;descriptor=J;signature=-")));
        CompatibilityReport moduleReport = ApiSurfaceComparator.compare(baseline, changedModule);
        assertFalse(moduleReport.moduleSame());
        assertFalse(moduleReport.passes());
    }
}
