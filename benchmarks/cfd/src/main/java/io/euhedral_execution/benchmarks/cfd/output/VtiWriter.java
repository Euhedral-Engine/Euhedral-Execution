package io.euhedral_execution.benchmarks.cfd.output;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.FieldFormat;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationState;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// Synchronous cell-data export from a completed state. Scratch is reused across all snapshots.
/// Appended blocks are uncompressed little-endian data with UInt64 byte counts and checked offsets.
public final class VtiWriter {
    private static final String[] NAMES = {"velocity", "density", "gauge_pressure", "solid", "obstacle_id"};
    private static final String[] TYPES = {"Float64", "Float64", "Float64", "UInt8", "Int32"};
    private static final int[] COMPONENTS = {3, 1, 1, 1, 1};
    private static final int[] WIDTHS = {8, 8, 8, 1, 4};
    private final double[] field = new double[5];

    public static long blockLength(long cells, int array) {
        if (cells < 0) throw new IllegalArgumentException("negative cell count");
        return Math.multiplyExact(Math.multiplyExact(cells, COMPONENTS[array]), WIDTHS[array]);
    }

    public static long offset(long cells, int array) {
        if (array < 0 || array > NAMES.length) throw new IllegalArgumentException("invalid array index");
        long offset = 0;
        for (int i = 0; i < array; i++) offset = Math.addExact(offset, Math.addExact(8, blockLength(cells, i)));
        return offset;
    }

    public void write(Path target, SimulationState state, CfdConfiguration configuration) throws IOException {
        if (!state.shape().equals(configuration.config().grid())
                || !state.physics().equals(configuration.physics()))
            throw new IllegalArgumentException("snapshot state and configuration differ");
        if (Files.exists(target)) throw new IOException("snapshot already exists: " + target);
        offset(state.shape().cellCount(), NAMES.length);
        AtomicOutput.checkInterrupted();
        AtomicOutput.write(target, temporary -> writeTemporary(temporary, state, configuration));
    }

    private void writeTemporary(Path file, SimulationState state, CfdConfiguration configuration) throws IOException {
        var shape = state.shape();
        boolean ascii = configuration.config().output().format() == FieldFormat.ASCII;
        double spacing = configuration.physics().voxelWidth();
        double time = state.completedSteps() * configuration.physics().timeStep();
        String extent = "0 " + shape.nx() + " 0 " + shape.ny() + " 0 " + shape.nz();
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 65_536))) {
            text(
                    out,
                    "<?xml version=\"1.0\"?>\n<VTKFile type=\"ImageData\" version=\"1.0\" byte_order=\"LittleEndian\" header_type=\"UInt64\">\n"
                            + "<ImageData WholeExtent=\"" + extent + "\" Origin=\"0 0 0\" Spacing=\"" + spacing + " "
                            + spacing + " " + spacing + "\">\n"
                            + "<FieldData><DataArray type=\"Float64\" Name=\"TimeValue\" NumberOfTuples=\"1\" format=\"ascii\">"
                            + time + "</DataArray>"
                            + "<DataArray type=\"Float64\" Name=\"time_"
                            + (configuration.physics().physicalUnits() ? "seconds" : "lattice_steps")
                            + "\" NumberOfTuples=\"1\" format=\"ascii\">" + time + "</DataArray></FieldData>\n"
                            + "<Piece Extent=\"" + extent
                            + "\"><PointData/><CellData Scalars=\"density\" Vectors=\"velocity\">\n");
            for (int array = 0; array < NAMES.length; array++) {
                text(
                        out,
                        "<DataArray type=\"" + TYPES[array] + "\" Name=\"" + NAMES[array] + "\" NumberOfComponents=\""
                                + COMPONENTS[array] + "\" format=\"" + (ascii ? "ascii" : "appended") + "\"");
                if (ascii) {
                    text(out, ">\n");
                    writeArray(out, state, configuration, array, true);
                    text(out, "</DataArray>\n");
                } else text(out, " offset=\"" + offset(shape.cellCount(), array) + "\"/>\n");
            }
            text(out, "</CellData></Piece></ImageData>\n");
            if (!ascii) {
                text(out, "<AppendedData encoding=\"raw\">_");
                for (int array = 0; array < NAMES.length; array++) {
                    out.writeLong(Long.reverseBytes(blockLength(shape.cellCount(), array)));
                    writeArray(out, state, configuration, array, false);
                }
                text(out, "</AppendedData>\n");
            }
            text(out, "</VTKFile>\n");
            AtomicOutput.checkInterrupted();
        }
    }

    private void writeArray(
            DataOutputStream out, SimulationState state, CfdConfiguration config, int array, boolean ascii)
            throws IOException {
        var shape = state.shape();
        for (int z = 0; z < shape.nz(); z++)
            for (int y = 0; y < shape.ny(); y++)
                for (int x = 0; x < shape.nx(); x++) {
                    if (x % 256 == 0) AtomicOutput.checkInterrupted();
                    int id = state.geometry().obstacleId(x + shape.nx() * (y + shape.ny() * z));
                    if (array < 3 && id == 0)
                        state.readField(x, y, z, field, config.physics().physicalUnits());
                    for (int c = 0; c < COMPONENTS[array]; c++) {
                        if (array < 3) {
                            double value = id != 0 ? 0 : field[array == 0 ? c + 1 : array == 1 ? 0 : 4];
                            if (ascii) text(out, Double.toString(value) + " ");
                            else out.writeLong(Long.reverseBytes(Double.doubleToRawLongBits(value)));
                        } else {
                            int value = array == 3 ? (id == 0 ? 0 : 1) : id;
                            if (ascii) text(out, Integer.toString(value) + " ");
                            else if (array == 3) out.writeByte(value);
                            else out.writeInt(Integer.reverseBytes(value));
                        }
                    }
                    if (ascii) text(out, "\n");
                }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }
}
