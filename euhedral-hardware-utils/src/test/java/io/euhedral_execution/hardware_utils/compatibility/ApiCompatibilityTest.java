package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ApiCompatibilityTest {

    @Test
    void preservesTheBranchPointSurfaceAndCompleteModuleDescriptor() throws Exception {
        ApiSurface baseline = ApiSurface.read(TestPaths.resource("api-900d8c50.tsv"));
        ApiSurface current = ApiSurfaceReader.read(TestPaths.classesDirectory());
        CompatibilityReport report = ApiSurfaceComparator.compare(baseline, current);
        Path reportPath = TestPaths.buildDirectory()
                .resolve("p0-compatibility/compatibility-report.txt");
        report.write(reportPath);

        assertEquals(5, current.moduleEntries().stream()
                .filter(entry -> entry.kind().equals("module-exports")).count());
        assertTrue(report.passes(), () -> "compatibility failure; see " + reportPath
                + System.lineSeparator() + report.render());
    }
}
