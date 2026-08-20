package calibration.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.comparisons.schema.CandidateComparison;
import calibration.comparisons.schema.ComparisonCompatibility;
import calibration.comparisons.schema.ComparisonResult;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.StateComparability;
import calibration.comparisons.schema.StateComparabilityComparison;
import calibration.config.ComparisonStrategy;
import calibration.infra.Constants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateComparabilityExportTest {

    @Test
    void exportsForkIdentityAndExplicitStateComponents(@TempDir Path tempDir) throws Exception {
        RunIdentity baseline = new RunIdentity("staged", "STAGED", "phase11", 0, 0, "/runs/staged/fork-1");
        RunIdentity candidate = new RunIdentity("skip", "SKIP", "phase11", 0, 2, "/runs/skip/fork-3");
        StateComparabilityComparison state = new StateComparabilityComparison(
                StateComparability.STATE_SHIFTED,
                0.25,
                0.25,
                0.0,
                3.0,
                2.5,
                -0.5,
                1.0,
                1.1,
                0.1,
                0.37,
                16,
                11,
                0.7,
                0.6,
                0.95,
                0.80,
                -0.15,
                0.42,
                0.02,
                0.05,
                0.03,
                -0.02,
                -0.08,
                0.01,
                0.03,
                0.022,
                0.085);
        CandidateComparison comparison = new CandidateComparison(
                0,
                baseline,
                candidate,
                null,
                ComparisonCompatibility.compatible(),
                List.of(),
                null,
                List.of(),
                null,
                state);

        ComparisonExport.exportStateComparabilityTsv(
                tempDir,
                new ComparisonResult(ComparisonStrategy.CROSS, List.of(comparison), null, List.of(), List.of()));

        List<String> lines = Files.readAllLines(tempDir.resolve(Constants.STATE_COMPARABILITY_TSV));
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("\t0\t2\t/runs/staged/fork-1\t/runs/skip/fork-3\tSTATE_SHIFTED\t"));
        assertTrue(Files.exists(tempDir.resolve(Constants.STATE_COMPARABILITY_CHECKSUM)));
    }
}
