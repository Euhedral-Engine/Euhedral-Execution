package io.euhedral_execution.benchmarks.cfd.geometry;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration.CfdPhysics;
import io.euhedral_execution.benchmarks.cfd.config.MemoryEstimate;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.Mesh;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.MeshUnits;
import io.euhedral_execution.benchmarks.cfd.config.Vector3;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/// Two streaming passes: inspection retains only metadata; loading allocates only after preflight.
/// Binary length takes precedence over the header text. ASCII tokens and names have fixed limits.
public final class StlReader {
    public static final long BYTES_PER_TRIANGLE = 2048;

    public record Info(
            Mesh mesh,
            String path,
            String encoding,
            int triangles,
            Vector3 min,
            Vector3 max,
            double weldTolerance,
            long preprocessingBytes,
            String sha256) {}

    private StlReader() {}

    public static Info inspect(Path configFile, Mesh mesh, CfdPhysics physics, long budget) {
        Path path = configFile.toAbsolutePath().getParent().resolve(mesh.file()).normalize();
        return scan(path, mesh, physics, budget, null);
    }

    static double[] load(Info expected, CfdPhysics physics) {
        double[] coordinates = new double[Math.multiplyExact(expected.triangles(), 9)];
        Info actual =
                scan(Path.of(expected.path()), expected.mesh(), physics, expected.preprocessingBytes(), coordinates);
        if (!actual.equals(expected)) throw error(expected.path(), -1, "mesh changed after inspection");
        return coordinates;
    }

    private static Info scan(Path path, Mesh mesh, CfdPhysics physics, long budget, double[] target) {
        String label = mesh.name() + " (" + path + ")";
        var transform = new Transform(mesh, physics);
        long limit = Math.min(MemoryEstimate.MAX_ARRAY_LENGTH / 12, budget / BYTES_PER_TRIANGLE);
        if (limit < 1) throw error(label, -1, "mesh preprocessing exceeds memory budget");
        try {
            if (!Files.isRegularFile(path)) throw error(label, -1, "not a regular mesh file");
            long length = Files.size(path);
            boolean binary = false;
            long count = 0;
            if (length >= 84) {
                try (var in = new DataInputStream(Files.newInputStream(path))) {
                    in.skipNBytes(80);
                    count = Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
                    binary = length == Math.addExact(84L, Math.multiplyExact(50L, count));
                }
            }
            if (binary && (count == 0 || count > limit))
                throw error(
                        label,
                        -1,
                        "triangle count " + count + " exceeds preprocessing limit " + limit + " or is empty");
            var digest = MessageDigest.getInstance("SHA-256");
            var bounds = new double[] {
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY
            };
            double[] triangle = new double[9];
            int triangles = 0;
            try (var in = new DigestInputStream(new BufferedInputStream(Files.newInputStream(path), 65536), digest)) {
                if (binary) {
                    if (in.readNBytes(84).length != 84) throw error(label, -1, "truncated binary header");
                    byte[] record = new byte[50];
                    var bytes = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
                    for (int t = 0; t < count; t++) {
                        if (in.readNBytes(record, 0, 50) != 50) throw error(label, t, "truncated binary triangle");
                        for (int i = 0; i < 12; i++) {
                            double value = bytes.getFloat(i * 4);
                            if (!Double.isFinite(value)) throw error(label, t, "non-finite coordinate/normal");
                            if (i >= 3) triangle[i - 3] = value;
                        }
                        accept(label, triangles++, triangle, transform, bounds, target);
                    }
                    if (in.read() != -1) throw error(label, triangles, "trailing binary bytes");
                } else {
                    var tokens = new Tokens(in, label);
                    tokens.expect("solid");
                    String name = tokens.restLine();
                    String next;
                    while (!(next = tokens.next()).equals("endsolid")) {
                        if (!next.equals("facet"))
                            throw error(label, triangles, "expected facet or endsolid at line " + tokens.line);
                        if (triangles >= limit)
                            throw error(label, triangles, "mesh preprocessing exceeds memory budget");
                        tokens.expect("normal");
                        for (int i = 0; i < 3; i++) tokens.number();
                        tokens.expect("outer");
                        tokens.expect("loop");
                        for (int v = 0; v < 3; v++) {
                            tokens.expect("vertex");
                            for (int a = 0; a < 3; a++) triangle[v * 3 + a] = tokens.number();
                        }
                        tokens.expect("endloop");
                        tokens.expect("endfacet");
                        accept(label, triangles++, triangle, transform, bounds, target);
                    }
                    String endName = tokens.restLine();
                    if (!endName.isEmpty() && !endName.equals(name))
                        throw error(label, triangles, "solid names differ");
                    if (!tokens.next().isEmpty()) throw error(label, triangles, "trailing ASCII data");
                    if (triangles == 0) throw error(label, -1, "empty STL");
                }
            }
            double tolerance = mesh.weldTolerance() == null ? physics.voxelWidth() * 1e-8 : mesh.weldTolerance();
            if (!Double.isFinite(tolerance) || tolerance <= 0 || tolerance > physics.voxelWidth() * 1e-3)
                throw error(label, -1, "weld tolerance must be positive and at most 0.001 voxel widths");
            for (double value : bounds)
                if (Math.ulp(value) * 16 > tolerance || Math.abs(value / tolerance) >= 0x1.0p60)
                    throw error(
                            label,
                            -1,
                            "coordinates exceed welding precision; translate nearer the origin or adjust tolerance");
            return new Info(
                    mesh,
                    path.toString(),
                    binary ? "binary" : "ASCII",
                    triangles,
                    new Vector3(bounds[0], bounds[1], bounds[2]),
                    new Vector3(bounds[3], bounds[4], bounds[5]),
                    tolerance,
                    Math.multiplyExact(BYTES_PER_TRIANGLE, triangles),
                    HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("mesh " + label + ": " + e.getMessage(), e);
        }
    }

    private static void accept(
            String label, int t, double[] triangle, Transform transform, double[] bounds, double[] target) {
        if (Thread.currentThread().isInterrupted()) throw error(label, t, "interrupted during STL reading");
        for (int v = 0; v < 3; v++) {
            transform.apply(triangle, v * 3);
            for (int a = 0; a < 3; a++) {
                double value = triangle[v * 3 + a];
                if (!Double.isFinite(value)) throw error(label, t, "transformed coordinate is non-finite");
                bounds[a] = Math.min(bounds[a], value);
                bounds[a + 3] = Math.max(bounds[a + 3], value);
            }
        }
        if (target != null) {
            if ((long) (t + 1) * 9 > target.length) throw error(label, t, "mesh grew after inspection");
            System.arraycopy(triangle, 0, target, t * 9, 9);
        }
    }

    static IllegalArgumentException error(String mesh, int triangle, String reason) {
        return new IllegalArgumentException(
                "mesh " + mesh + (triangle >= 0 ? " triangle " + triangle : "") + ": " + reason);
    }

    private static final class Transform {
        final Mesh mesh;
        final double unit, cx, sx, cy, sy, cz, sz;

        Transform(Mesh mesh, CfdPhysics physics) {
            this.mesh = mesh;
            if (!physics.physicalUnits() && mesh.units() != MeshUnits.LATTICE)
                throw error(mesh.name(), -1, "SI mesh units require physical simulation parameters");
            unit = switch (mesh.units()) {
                case LATTICE -> physics.voxelWidth();
                case METERS -> 1;
                case MILLIMETERS -> 0.001;
                case CENTIMETERS -> 0.01;
                case INCHES -> 0.0254;
            };
            double x = Math.toRadians(mesh.rotationDegrees().x() % 360),
                    y = Math.toRadians(mesh.rotationDegrees().y() % 360),
                    z = Math.toRadians(mesh.rotationDegrees().z() % 360);
            cx = Math.cos(x);
            sx = Math.sin(x);
            cy = Math.cos(y);
            sy = Math.sin(y);
            cz = Math.cos(z);
            sz = Math.sin(z);
            for (double scale : new double[] {
                mesh.scale().x(), mesh.scale().y(), mesh.scale().z()
            })
                if (!Double.isFinite(unit * scale) || unit * scale == 0)
                    throw error(mesh.name(), -1, "singular/non-finite transform");
        }

        void apply(double[] p, int i) {
            double x = p[i] * (unit * mesh.scale().x()),
                    y = p[i + 1] * (unit * mesh.scale().y()),
                    z = p[i + 2] * (unit * mesh.scale().z());
            double yy = cx * y - sx * z, zz = sx * y + cx * z;
            double xx = cy * x + sy * zz;
            z = -sy * x + cy * zz;
            p[i] = cz * xx - sz * yy + mesh.translation().x();
            p[i + 1] = sz * xx + cz * yy + mesh.translation().y();
            p[i + 2] = z + mesh.translation().z();
        }
    }

    private static final class Tokens {
        final InputStream in;
        final String label;
        int separator = '\n', line = 1;

        Tokens(InputStream in, String label) {
            this.in = in;
            this.label = label;
        }

        int read() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw error(label, -1, "interrupted during ASCII reading");
            int c = in.read();
            if (c > 126 || (c >= 0 && c < 32 && c != '\n' && c != '\r' && c != '\t'))
                throw error(
                        label, -1, "invalid ASCII byte at line " + line + "; binary STL requires exact count/length");
            if (c == '\n') line++;
            return c;
        }

        String next() throws IOException {
            var token = new StringBuilder();
            int c;
            do {
                c = read();
            } while (c >= 0 && Character.isWhitespace(c));
            while (c >= 0 && !Character.isWhitespace(c)) {
                if (token.length() == 128) throw error(label, -1, "ASCII token exceeds 128 bytes at line " + line);
                token.append((char) c);
                c = read();
            }
            separator = c;
            return token.toString();
        }

        String restLine() throws IOException {
            if (separator == '\n' || separator == -1) return "";
            var name = new StringBuilder();
            int c;
            while ((c = read()) != -1 && c != '\n') {
                if (name.length() == 4096) throw error(label, -1, "solid name exceeds 4096 bytes");
                name.append((char) c);
            }
            separator = c;
            return name.toString().trim();
        }

        void expect(String expected) throws IOException {
            if (!next().equals(expected)) throw error(label, -1, "expected " + expected + " at line " + line);
        }

        double number() throws IOException {
            try {
                String token = next();
                if (!token.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
                    throw new NumberFormatException();
                double value = Double.parseDouble(token);
                if (!Double.isFinite(value)) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException e) {
                throw error(label, -1, "invalid/non-finite number at line " + line);
            }
        }
    }
}
