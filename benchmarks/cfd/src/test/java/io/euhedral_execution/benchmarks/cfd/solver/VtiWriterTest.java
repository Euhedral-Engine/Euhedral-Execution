package io.euhedral_execution.benchmarks.cfd.solver;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.*;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.*;
import io.euhedral_execution.benchmarks.cfd.output.*;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Isolated
class VtiWriterTest {
    @TempDir
    Path directory;

    static ControlPlaneLattice lattice;

    @BeforeAll
    static void start() {
        lattice = CfdTestRuntime.singleWorker();
    }

    @AfterAll
    static void stop() {
        lattice.close();
    }

    private CfdConfiguration config(FieldFormat format) {
        return ConfigLoader.resolve(
                directory.resolve("fixture.json"),
                new SimulationConfig(
                        1,
                        new GridShape(3, 4, 5),
                        new Physics(
                                2.0,
                                null,
                                new Physical(0.02, 0.004, 1000, 0.01, null, new Vector3(0.1, -0.2, 0.3)),
                                null),
                        new Geometry(
                                null,
                                List.of(new Box(9, new Vector3(0.02, 0.04, 0.06), new Vector3(0.04, 0.06, 0.08))),
                                null,
                                null),
                        new Execution(2L, null, null, null),
                        new Output(directory.toString(), 1L, format),
                        null),
                100_000_000);
    }

    private void fill(SimulationState state, CfdConfiguration config) {
        var a = config.physics().acceleration();
        for (int z = 0; z < 5; z++)
            for (int y = 0; y < 4; y++)
                for (int x = 0; x < 3; x++) {
                    int index = x + 3 * (y + 4 * z);
                    double key = x + 10 * y + 100 * z;
                    double rho = 2 + 0.0001 * key,
                            ux = 0.002 + 0.00001 * key,
                            uy = -0.003 + 0.000002 * key,
                            uz = 0.004 - 0.000003 * key;
                    for (int q = 0; q < 19; q++)
                        state.current()[q][index] = D3Q19.equilibrium(q, rho, ux, uy, uz)
                                + D3Q19.guo(q, rho, ux, uy, uz, a.x(), a.y(), a.z()) / 2;
                }
    }

    @ParameterizedTest
    @EnumSource(FieldFormat.class)
    void exportsNonCubicCellDataWithPhysicalUnitsAndForceCorrection(FieldFormat format) throws Exception {
        var config = config(format);
        try (var simulation = new SerialSimulation(config, lattice)) {
            fill(simulation.state(), config);
            Path file = directory.resolve("known.vti");
            new VtiWriter().write(file, simulation.state(), config);
            double[][] arrays = read(file, format);
            for (int z = 0; z < 5; z++)
                for (int y = 0; y < 4; y++)
                    for (int x = 0; x < 3; x++) {
                        int index = x + 3 * (y + 4 * z);
                        double key = x + 10 * y + 100 * z;
                        boolean solid = x == 1 && y == 2 && z == 3;
                        assertEquals(solid ? 1 : 0, arrays[3][index], 0);
                        assertEquals(solid ? 9 : 0, arrays[4][index], 0);
                        assertEquals(solid ? 0 : (2 + 0.0001 * key) * 500, arrays[1][index], 1e-10);
                        assertEquals(solid ? 0 : 0.0001 * key / 3 * 12500, arrays[2][index], 1e-10);
                        assertEquals(solid ? 0 : (0.002 + 0.00001 * key) * 5, arrays[0][index * 3], 1e-14);
                        assertEquals(solid ? 0 : (-0.003 + 0.000002 * key) * 5, arrays[0][index * 3 + 1], 1e-14);
                        assertEquals(solid ? 0 : (0.004 - 0.000003 * key) * 5, arrays[0][index * 3 + 2], 1e-14);
                    }
            assertThrows(java.io.IOException.class, () -> new VtiWriter().write(file, simulation.state(), config));
        }
    }

    private double[][] read(Path file, FieldFormat format) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        int start = text.indexOf("<AppendedData encoding=\"raw\">_");
        int dataStart = start < 0 ? -1 : start + "<AppendedData encoding=\"raw\">_".length();
        String xml = start < 0 ? text : text.substring(0, start) + "</VTKFile>";
        var document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.US_ASCII)));
        var image =
                (org.w3c.dom.Element) document.getElementsByTagName("ImageData").item(0);
        assertEquals("0 3 0 4 0 5", image.getAttribute("WholeExtent"));
        assertEquals("0 0 0", image.getAttribute("Origin"));
        assertEquals("0.02 0.02 0.02", image.getAttribute("Spacing"));
        var data = ((org.w3c.dom.Element)
                        document.getElementsByTagName("CellData").item(0))
                .getElementsByTagName("DataArray");
        assertEquals(5, data.getLength());
        double[][] arrays = new double[5][];
        long expectedOffset = 0;
        for (int array = 0; array < 5; array++) {
            var element = (org.w3c.dom.Element) data.item(array);
            arrays[array] = new double[array == 0 ? 180 : 60];
            if (format == FieldFormat.ASCII) {
                String[] tokens = element.getTextContent().trim().split("\\s+");
                assertEquals(arrays[array].length, tokens.length);
                for (int i = 0; i < tokens.length; i++) arrays[array][i] = Double.parseDouble(tokens[i]);
            } else {
                assertEquals(expectedOffset, Long.parseLong(element.getAttribute("offset")));
                var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                buffer.position(Math.toIntExact(dataStart + expectedOffset));
                long length = buffer.getLong();
                assertEquals(arrays[array].length * (array < 3 ? 8 : array == 3 ? 1 : 4), length);
                for (int i = 0; i < arrays[array].length; i++)
                    arrays[array][i] = array < 3
                            ? buffer.getDouble()
                            : array == 3 ? Byte.toUnsignedInt(buffer.get()) : buffer.getInt();
                expectedOffset += 8 + length;
            }
        }
        return arrays;
    }

    @Test
    void offsetsRemainLongAndOverflowIsRejected() {
        assertEquals(7_200_000_000L, VtiWriter.blockLength(300_000_000L, 0));
        assertEquals(7_200_000_008L, VtiWriter.offset(300_000_000L, 1));
        assertEquals(13_500_000_040L, VtiWriter.offset(300_000_000L, 5));
        assertThrows(ArithmeticException.class, () -> VtiWriter.offset(Long.MAX_VALUE, 5));
    }

    @Test
    void failedOrInterruptedSnapshotDoesNotChangeThePublishedCollection() throws Exception {
        var config = config(FieldFormat.APPENDED);
        try (var simulation = new SerialSimulation(config, lattice)) {
            var writer = new VtiWriter();
            var pvd = new PvdWriter(directory.resolve("flow.pvd"), true);
            Path good = directory.resolve("good.vti"), bad = directory.resolve("bad.vti");
            writer.write(good, simulation.state(), config);
            pvd.append(0, 0, good);
            byte[] previous = Files.readAllBytes(directory.resolve("flow.pvd"));
            simulation.state().current()[0][59] = Double.NaN;
            assertThrows(SimulationException.class, () -> writer.write(bad, simulation.state(), config));
            assertFalse(Files.exists(bad));
            assertThrows(java.io.IOException.class, () -> pvd.append(1, 0.004, bad));
            Thread.currentThread().interrupt();
            try {
                assertThrows(java.io.InterruptedIOException.class, () -> writer.write(bad, simulation.state(), config));
            } finally {
                Thread.interrupted();
            }
            assertArrayEquals(previous, Files.readAllBytes(directory.resolve("flow.pvd")));
            try (var entries = Files.list(directory)) {
                assertFalse(entries.anyMatch(path -> path.toString().endsWith(".tmp")));
            }
        }
    }

    @Test
    void interruptedRunKeepsCompletedFramesAndRecordsStatus() throws Exception {
        var config = config(FieldFormat.APPENDED);
        Path run;
        try (var simulation = new SerialSimulation(config, lattice);
                var output = new RunOutput(config)) {
            run = output.directory();
            output.record(simulation.state(), 10);
            simulation.step();
            output.record(simulation.state(), 20);
            simulation.step();
            Thread.currentThread().interrupt();
            try {
                assertThrows(java.io.InterruptedIOException.class, () -> output.record(simulation.state(), 30));
                output.finish(RunOutput.Status.INTERRUPTED, null);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
        var status = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(run.resolve("status.json").toFile());
        assertEquals("INTERRUPTED", status.get("status").asText());
        assertEquals(2, status.get("completedSteps").asLong());
        assertEquals(1, status.get("lastExportedStep").asLong());
        assertTrue(Files.isRegularFile(run.resolve("frame-000000000001.vti")));
        assertFalse(Files.exists(run.resolve("frame-000000000002.vti")));
        assertFalse(Files.readString(run.resolve("flow.pvd")).contains("000000000002"));
    }

    @Test
    @Tag("integration")
    @EnabledIfEnvironmentVariable(named = "CFD_VTK_PYTHON", matches = ".+")
    void installedVtkReaderLoadsBothFormats() throws Exception {
        for (FieldFormat format : FieldFormat.values()) {
            var config = config(format);
            try (var simulation = new SerialSimulation(config, lattice)) {
                fill(simulation.state(), config);
                Path file = directory.resolve(format + ".vti");
                new VtiWriter().write(file, simulation.state(), config);
                var process = new ProcessBuilder(
                                System.getenv("CFD_VTK_PYTHON"), "src/test/python/verify_vti.py", file.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(directory.resolve(format + ".log").toFile())
                        .start();
                try {
                    assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
                    assertEquals(0, process.exitValue(), Files.readString(directory.resolve(format + ".log")));
                } finally {
                    process.destroyForcibly();
                }
            }
        }
    }
}
