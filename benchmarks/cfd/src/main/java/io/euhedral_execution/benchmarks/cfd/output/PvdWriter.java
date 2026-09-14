package io.euhedral_execution.benchmarks.cfd.output;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// Each rewrite streams the prior collection; no in-memory list grows with snapshot count.
public final class PvdWriter {
    private static final String END = "</Collection></VTKFile>\n";
    private final Path file;
    private final byte[] copyBuffer = new byte[65_536];
    private long lastStep = -1;

    public PvdWriter(Path file, boolean physical) throws IOException {
        this.file = file;
        if (Files.exists(file)) throw new IOException("collection already exists: " + file);
        AtomicOutput.write(
                file,
                temporary -> Files.writeString(
                        temporary,
                        "<?xml version=\"1.0\"?>\n<VTKFile type=\"Collection\" version=\"0.1\" byte_order=\"LittleEndian\">\n"
                                + "<!-- time_unit=" + (physical ? "seconds" : "lattice_steps") + " -->\n<Collection>\n"
                                + END));
    }

    public void append(long step, double time, Path snapshot) throws IOException {
        if (step <= lastStep || !Double.isFinite(time) || time < 0)
            throw new IllegalArgumentException("invalid snapshot time/order");
        if (!snapshot.getParent().equals(file.getParent()) || !Files.isRegularFile(snapshot))
            throw new IOException("collection needs a completed sibling snapshot");
        String name = snapshot.getFileName().toString();
        if (!name.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("unsafe snapshot filename");
        AtomicOutput.checkInterrupted();
        AtomicOutput.write(file, temporary -> {
            try (var in = Files.newInputStream(file);
                    var out = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                long remaining = Files.size(file) - END.length();
                while (remaining > 0) {
                    AtomicOutput.checkInterrupted();
                    int count = in.read(copyBuffer, 0, (int) Math.min(copyBuffer.length, remaining));
                    if (count < 0) throw new IOException("truncated collection");
                    out.write(copyBuffer, 0, count);
                    remaining -= count;
                }
                out.write(("<DataSet timestep=\"" + time + "\" group=\"\" part=\"0\" file=\"" + name + "\"/>\n" + END)
                        .getBytes(StandardCharsets.US_ASCII));
                AtomicOutput.checkInterrupted();
            }
        });
        lastStep = step;
    }
}
