package io.euhedral_execution.benchmarks.cfd.validation;

import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Dense primitive storage is allocated once per exported snapshot, outside frame execution.
/// The exchange coordinates and fields are physical (identity scales for lattice-unit cases).
public final class Snapshot {
    public static final String HEADER = "x\ty\tz\ttime\tmaterial\tobstacle\tux\tuy\tuz\trho\tpressure";
    final GridShape shape;
    final double spacing, time;
    final int[] ids;
    final double[][] fields;

    Snapshot(GridShape shape, double spacing, double time) {
        this.shape = shape;
        this.spacing = spacing;
        this.time = time;
        ids = new int[Math.toIntExact(shape.cellCount())];
        fields = new double[5][ids.length];
    }

    public static Snapshot read(Path path, GridShape shape, double spacing, double time) throws IOException {
        return read(path, shape, spacing, time, 1, 1, 1, 1);
    }

    /// Explicit exchange-unit conversion; never infer units from magnitudes or column order.
    static Snapshot read(
            Path path,
            GridShape shape,
            double spacing,
            double time,
            double lengthUnit,
            double timeUnit,
            double densityUnit,
            double velocityUnit)
            throws IOException {
        for (double unit : new double[] {spacing, lengthUnit, timeUnit, densityUnit, velocityUnit})
            ValidationSuite.require(Double.isFinite(unit) && unit > 0, "snapshot scales must be positive and finite");
        var result = new Snapshot(shape, spacing, time);
        var seen = new boolean[result.ids.length];
        int rows = 0;
        try (var reader = Files.newBufferedReader(path)) {
            if (!HEADER.equals(reader.readLine())) throw new IOException("snapshot header differs: " + path);
            double[] v = new double[11];
            for (String line; (line = reader.readLine()) != null; ) {
                String[] values = line.split("\t", -1);
                if (values.length != 11) throw new IOException("snapshot row has wrong column count");
                try {
                    for (int i = 0; i < v.length; i++) {
                        v[i] = Double.parseDouble(values[i]);
                        if (!Double.isFinite(v[i])) throw new NumberFormatException("non-finite sample");
                    }
                    int x = coordinate(v[0] * lengthUnit, spacing, shape.nx());
                    int y = coordinate(v[1] * lengthUnit, spacing, shape.ny());
                    int z = coordinate(v[2] * lengthUnit, spacing, shape.nz());
                    if (!sameTime(v[3] * timeUnit, time)) throw new NumberFormatException("sample time differs");
                    int id = (int) v[5];
                    if (id != v[5] || v[4] != (id == 0 ? 0 : 1))
                        throw new NumberFormatException("material/obstacle mismatch");
                    int index = x + shape.nx() * (y + shape.ny() * z);
                    if (seen[index]) throw new NumberFormatException("duplicate coordinate");
                    seen[index] = true;
                    result.ids[index] = id;
                    for (int a = 0; a < 3; a++) result.fields[a][index] = v[6 + a] * velocityUnit;
                    result.fields[3][index] = v[9] * densityUnit;
                    result.fields[4][index] = v[10] * densityUnit * velocityUnit * velocityUnit;
                    for (var field : result.fields)
                        if (!Double.isFinite(field[index]))
                            throw new NumberFormatException("unit conversion overflows");
                    if (id == 0 && result.fields[3][index] <= 0)
                        throw new NumberFormatException("nonpositive fluid density");
                } catch (IllegalArgumentException e) {
                    throw new IOException("invalid snapshot " + path + " row " + (rows + 2) + ": " + e.getMessage(), e);
                }
                rows++;
            }
        }
        if (rows != result.ids.length) throw new IOException("incomplete snapshot: " + rows + "/" + result.ids.length);
        return result;
    }

    private static int coordinate(double value, double spacing, int size) {
        double cell = value / spacing - 0.5;
        long rounded = Math.round(cell);
        if (rounded < 0 || rounded >= size || !close(cell, rounded))
            throw new NumberFormatException("coordinate does not match grid");
        return (int) rounded;
    }

    static boolean sameTime(double a, double b) {
        return Double.isFinite(a)
                && Double.isFinite(b)
                && Math.abs(a - b) <= 32 * Math.ulp(Math.max(Math.abs(a), Math.abs(b)));
    }

    static boolean close(double a, double b) {
        return Math.abs(a - b) <= 1e-12 * Math.max(1, Math.max(Math.abs(a), Math.abs(b)));
    }
}
