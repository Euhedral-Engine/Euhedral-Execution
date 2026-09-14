package io.euhedral_execution.benchmarks.cfd.benchmark;

import io.euhedral_execution.benchmarks.cfd.solver.SimulationState;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/// Complete streamed fields, including solids and reductions. No sampled checksum substitutes for equivalence.
public final class PopulationReference {
    private PopulationReference() {}

    static String requireEvidence(BenchmarkJob job) throws IOException {
        var evidence = BenchmarkSuite.JSON.readTree(
                Path.of(job.reference()).getParent().resolve("reference.json").toFile());
        BenchmarkSuite.require(
                evidence.path("status").asText().equals("COMPLETED")
                        && evidence.path("caseIdentity").asText().equals(job.caseIdentity())
                        && evidence.path("artifactIdentity").asText().equals(job.artifactIdentity())
                        && evidence.path("referenceSha256").asText().equals(job.referenceSha256()),
                "missing or stale full reference evidence");
        String backend = evidence.path("backend").asText();
        BenchmarkSuite.require(
                java.util.List.of("serial", "fjp", "static", "euhedral").contains(backend),
                "unknown reference backend");
        return backend;
    }

    public static void write(Path file, SimulationState state, String identity) throws IOException {
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 65536))) {
            out.writeUTF("CFD-POPULATIONS-1");
            out.writeUTF(identity);
            out.writeLong(state.completedSteps());
            var shape = state.shape();
            out.writeInt(shape.nx());
            out.writeInt(shape.ny());
            out.writeInt(shape.nz());
            visit(state, out, null);
        }
    }

    public static void verify(Path file, SimulationState state, String identity) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 65536))) {
            BenchmarkSuite.require(
                    in.readUTF().equals("CFD-POPULATIONS-1") && in.readUTF().equals(identity),
                    "reference identity mismatch");
            BenchmarkSuite.require(in.readLong() == state.completedSteps(), "reference step mismatch");
            var shape = state.shape();
            BenchmarkSuite.require(
                    in.readInt() == shape.nx() && in.readInt() == shape.ny() && in.readInt() == shape.nz(),
                    "reference grid mismatch");
            visit(state, null, in);
            BenchmarkSuite.require(in.read() == -1, "trailing reference populations");
        }
    }

    private static void visit(SimulationState state, DataOutputStream out, DataInputStream in) throws IOException {
        var shape = state.shape();
        double[] fields = new double[5];
        for (int z = 0; z < shape.nz(); z++) {
            for (int y = 0; y < shape.ny(); y++) {
                for (int x = 0; x < shape.nx(); x++) {
                    int id = state.geometry().obstacleId(x + shape.nx() * (y + shape.ny() * z));
                    value(id, out, in);
                    for (int q = 0; q < 19; q++) {
                        value(state.population(q, x, y, z), out, in);
                    }
                    if (id == 0) {
                        state.readField(x, y, z, fields, false);
                        for (double field : fields) {
                            value(field, out, in);
                        }
                    }
                }
            }
        }
        var flow = state.flowDiagnostics();
        value(flow.massChange(), out, in);
        value(flow.inletFlux(), out, in);
        value(flow.outletFlux(), out, in);
        value(flow.macroscopicInletFlux(), out, in);
        value(flow.macroscopicOutletFlux(), out, in);
        value(flow.obstacleCount(), out, in);
        for (int slot = 0; slot < flow.obstacleCount(); slot++) {
            int id = flow.obstacleId(slot);
            value(id, out, in);
            for (int axis = 0; axis < 3; axis++) {
                value(flow.force(id, axis), out, in);
            }
        }
    }

    private static void value(double value, DataOutputStream out, DataInputStream in) throws IOException {
        BenchmarkSuite.require(Double.isFinite(value), "non-finite complete field");
        long bits = Double.doubleToLongBits(value);
        if (out != null) {
            out.writeLong(bits);
        } else if (in.readLong() != bits) {
            throw new IOException("complete population, field or reduction differs from qualified reference");
        }
    }
}
