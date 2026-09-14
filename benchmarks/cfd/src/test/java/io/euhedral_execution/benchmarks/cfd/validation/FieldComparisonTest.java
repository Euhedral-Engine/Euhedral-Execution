package io.euhedral_execution.benchmarks.cfd.validation;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FieldComparisonTest {
    @TempDir
    Path directory;

    static final GridShape SHAPE = new GridShape(2, 2, 2);
    static final Map<String, ValidationSuite.Tolerance> LIMITS = Map.of(
            "velocity", new ValidationSuite.Tolerance(1e-10, 1e-8, .01),
            "density", new ValidationSuite.Tolerance(1e-10, 1e-8, 1),
            "pressure", new ValidationSuite.Tolerance(1e-10, 1e-8, .001),
            "mass", new ValidationSuite.Tolerance(1e-10, 1e-8, 8),
            "force", new ValidationSuite.Tolerance(1e-10, 1e-8, .001));

    static Snapshot uniform() {
        var s = new Snapshot(SHAPE, 1, 2);
        java.util.Arrays.fill(s.fields[3], 1);
        return s;
    }

    @Test
    void zeroReferenceFieldsUseDeclaredFallbackScale() {
        var a = uniform();
        var b = uniform();
        var exact = FieldComparison.compare(a, b, ValidationSuite.Gauge.ABSOLUTE, LIMITS);
        assertTrue(exact.passed());
        assertEquals(0, exact.metrics().get("velocity").scaledMaxError());
        a.fields[0][3] = 1e-4;
        var result = FieldComparison.compare(a, b, ValidationSuite.Gauge.ABSOLUTE, LIMITS);
        assertFalse(result.passed());
        assertEquals(1e-4 / Math.sqrt(8), result.metrics().get("velocity").rmsError(), 1e-15);
        assertEquals(1.5, result.metrics().get("velocity").worstX());
        assertEquals(1.5, result.metrics().get("velocity").worstY());
    }

    @Test
    void gaugeOffsetsOnlyDisappearUnderDeclaredMeanAlignment() {
        var a = uniform();
        var b = uniform();
        java.util.Arrays.fill(a.fields[4], 5);
        assertTrue(FieldComparison.compare(a, b, ValidationSuite.Gauge.FLUID_MEAN, LIMITS)
                .passed());
        assertFalse(FieldComparison.compare(a, b, ValidationSuite.Gauge.ABSOLUTE, LIMITS)
                .passed());
        a.fields[4][0] += .01;
        assertFalse(FieldComparison.compare(a, b, ValidationSuite.Gauge.FLUID_MEAN, LIMITS)
                .passed());
    }

    @Test
    void velocityComponentPermutationCannotPass() {
        var a = uniform();
        var b = uniform();
        a.fields[0][0] = .01;
        b.fields[1][0] = .01;
        assertFalse(FieldComparison.compare(a, b, ValidationSuite.Gauge.FLUID_MEAN, LIMITS)
                .passed());
    }

    @Test
    void materialIdsMustMatchEvenWhenBothCellsAreSolid() {
        var a = uniform();
        var b = uniform();
        a.ids[0] = 1;
        b.ids[0] = 2;
        var result = FieldComparison.compare(a, b, ValidationSuite.Gauge.FLUID_MEAN, LIMITS);
        assertFalse(result.passed());
        assertEquals(1, result.maskMismatches());
        assertEquals(7, result.fluidSamples());
    }

    @Test
    void readsReorderedRowsAndNormalizesDeclaredUnits() throws Exception {
        Path path = write("units.tsv", true, 10, 100, 1000, 10);
        var data = Snapshot.read(path, SHAPE, 1, 2, .1, .01, .001, .1);
        assertEquals(1, data.fields[3][0]);
        assertEquals(.01, data.fields[0][0], 1e-16);
        assertEquals(2, data.fields[4][0], 1e-14);
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "missing", "time", "coordinate", "nan", "material", "header", "absent"})
    void malformedSnapshotsFailClosed(String mode) throws Exception {
        Path path = write("bad.tsv", false, 1, 1, 1, 1);
        var lines = Files.readAllLines(path);
        switch (mode) {
            case "duplicate" -> lines.set(2, lines.get(1));
            case "missing" -> lines.removeLast();
            case "time" -> lines.set(1, lines.get(1).replace("\t2.0\t0\t0", "\t3.0\t0\t0"));
            case "coordinate" ->
                lines.set(1, "9" + lines.get(1).substring(lines.get(1).indexOf('\t')));
            case "nan" -> lines.set(1, lines.get(1).replace("0.01", "NaN"));
            case "material" -> lines.set(1, lines.get(1).replace("\t0\t0\t", "\t1\t0\t"));
            case "header" -> lines.set(0, Snapshot.HEADER.replace("ux\tuy", "uy\tux"));
            case "absent" -> {
                assertThrows(IOException.class, () -> Snapshot.read(directory.resolve("missing"), SHAPE, 1, 2));
                return;
            }
        }
        Files.write(path, lines);
        assertThrows(IOException.class, () -> Snapshot.read(path, SHAPE, 1, 2));
    }

    @Test
    void changingForceSignFailsItsIndependentTolerance() throws Exception {
        Path a = directory.resolve("a.tsv"), b = directory.resolve("b.tsv");
        Files.writeString(a, "id\tfx\tfy\tfz\n1\t.01\t0\t0\n");
        Files.writeString(b, "id\tfx\tfy\tfz\n1\t-.01\t0\t0\n");
        var fixture = new ValidationSuite.Case(
                "force",
                "case.json",
                List.of("force"),
                ValidationSuite.Mode.MATCHED_DISCRETIZATION,
                ValidationSuite.Gauge.FLUID_MEAN,
                List.of(0L, 10L),
                LIMITS,
                null,
                "fixture",
                List.of());
        assertFalse(ValidationRunner.compareForces(a, b, fixture).get(1).passed());
    }

    private Path write(String name, boolean reverse, double length, double time, double density, double velocity)
            throws IOException {
        var lines = new java.util.ArrayList<String>();
        for (int z = 0; z < 2; z++)
            for (int y = 0; y < 2; y++)
                for (int x = 0; x < 2; x++)
                    lines.add((x + .5) * length + "\t" + (y + .5) * length + "\t" + (z + .5) * length + "\t" + 2 * time
                            + "\t0\t0\t" + .01 * velocity + "\t0\t0\t" + density + "\t"
                            + 2 * density * velocity * velocity);
        if (reverse) java.util.Collections.reverse(lines);
        lines.addFirst(Snapshot.HEADER);
        Path path = directory.resolve(name);
        Files.write(path, lines);
        return path;
    }
}
