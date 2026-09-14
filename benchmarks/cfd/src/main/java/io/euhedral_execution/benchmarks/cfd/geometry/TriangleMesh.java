package io.euhedral_execution.benchmarks.cfd.geometry;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration.CfdPhysics;
import java.util.*;

/// Setup-owned welded triangles and a balanced AABB tree. No mesh objects enter a simulation frame.
final class TriangleMesh {
    final StlReader.Info info;
    final double[] points;
    final int[] vertices, shell, order, left, right, start, count;
    final double[] bounds;
    int nodes, vertexCount, shells, cavities;

    TriangleMesh(StlReader.Info info, CfdPhysics physics) {
        this(info, physics, false);
    }

    TriangleMesh(StlReader.Info info, CfdPhysics physics, boolean deferIntersections) {
        this.info = info;
        points = StlReader.load(info, physics);
        int n = info.triangles();
        vertices = new int[n * 3];
        shell = new int[n];
        order = new int[n];
        left = new int[n * 2];
        right = new int[n * 2];
        start = new int[n * 2];
        count = new int[n * 2];
        bounds = new double[n * 12];
        weld();
        topology();
        for (int t = 0; t < n; t++) order[t] = t;
        build(0, n);
        if (!deferIntersections) {
            double[] a = new double[9], b = new double[9];
            Runnable checkpoint = () -> checkInterrupted(0);
            for (int t = 0; t < n; t++) validateTriangle(t, a, b, checkpoint);
            finishValidation();
        }
    }

    private record Key(long x, long y, long z) {}

    private static final class Edge {
        int triangle, count = 1, orientation;

        Edge(int t, int o) {
            triangle = t;
            orientation = o;
        }
    }

    private void weld() {
        var bins = new HashMap<Key, ArrayList<Integer>>();
        double h = info.weldTolerance();
        for (int v = 0; v < vertices.length; v++) {
            checkInterrupted(v / 3);
            int i = v * 3;
            long x = (long) Math.floor(points[i] / h),
                    y = (long) Math.floor(points[i + 1] / h),
                    z = (long) Math.floor(points[i + 2] / h);
            int representative = Integer.MAX_VALUE;
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        var bucket = bins.get(new Key(x + dx, y + dy, z + dz));
                        if (bucket == null) continue;
                        for (int candidate : bucket) {
                            int j = candidate * 3;
                            if (Math.max(
                                            Math.abs(points[i] - points[j]),
                                            Math.max(
                                                    Math.abs(points[i + 1] - points[j + 1]),
                                                    Math.abs(points[i + 2] - points[j + 2])))
                                    <= h) representative = Math.min(representative, candidate);
                        }
                    }
            if (representative == Integer.MAX_VALUE) {
                bins.computeIfAbsent(new Key(x, y, z), ignored -> new ArrayList<>())
                        .add(v);
                vertices[v] = vertexCount++;
            } else {
                vertices[v] = vertices[representative];
                System.arraycopy(points, representative * 3, points, i, 3);
            }
        }
    }

    private void topology() {
        var edges = new HashMap<Long, Edge>();
        int[] fans = new int[vertices.length];
        for (int v = 0; v < fans.length; v++) fans[v] = v;
        for (int t = 0; t < shell.length; t++) shell[t] = t;
        for (int t = 0; t < shell.length; t++) {
            checkInterrupted(t);
            int i = t * 9;
            double ux = points[i + 3] - points[i],
                    uy = points[i + 4] - points[i + 1],
                    uz = points[i + 5] - points[i + 2];
            double vx = points[i + 6] - points[i],
                    vy = points[i + 7] - points[i + 1],
                    vz = points[i + 8] - points[i + 2];
            double area = Math.hypot(Math.hypot(uy * vz - uz * vy, uz * vx - ux * vz), ux * vy - uy * vx);
            double length = Math.max(Math.hypot(Math.hypot(ux, uy), uz), Math.hypot(Math.hypot(vx, vy), vz));
            if (!Double.isFinite(area) || area <= info.weldTolerance() * length || length == 0)
                fail(t, "degenerate triangle after welding");
            for (int e = 0; e < 3; e++) {
                int a = vertices[t * 3 + e], b = vertices[t * 3 + (e + 1) % 3];
                if (a == b) fail(t, "degenerate welded edge");
                long key = ((long) Math.min(a, b) << 32) | Integer.toUnsignedLong(Math.max(a, b));
                int orientation = a < b ? 1 : -1;
                Edge old = edges.putIfAbsent(key, new Edge(t, orientation));
                if (old != null) {
                    if (++old.count > 2) fail(t, "nonmanifold edge " + a + "-" + b);
                    if (old.orientation == orientation)
                        fail(t, "inconsistent winding at edge " + a + "-" + b + " with triangle " + old.triangle);
                    shell[root(t)] = root(old.triangle);
                    for (int v = 0; v < 3; v++) {
                        int oldCorner = old.triangle * 3 + v, id = vertices[oldCorner];
                        if (id == a) fans[fanRoot(fans, t * 3 + e)] = fanRoot(fans, oldCorner);
                        if (id == b) fans[fanRoot(fans, t * 3 + (e + 1) % 3)] = fanRoot(fans, oldCorner);
                    }
                }
            }
        }
        for (var edge : edges.entrySet())
            if (edge.getValue().count != 2)
                fail(
                        edge.getValue().triangle,
                        "open edge " + (edge.getKey() >>> 32) + "-" + (edge.getKey() & 0xffffffffL));
        /// Compress before replacing roots with compact shell numbers.
        for (int t = 0; t < shell.length; t++) shell[t] = root(t);
        var ids = new HashMap<Integer, Integer>();
        for (int t = 0; t < shell.length; t++) shell[t] = ids.computeIfAbsent(shell[t], ignored -> ids.size());
        shells = ids.size();
        int[] owner = new int[vertexCount];
        Arrays.fill(owner, -1);
        for (int t = 0; t < shell.length; t++)
            for (int v = 0; v < 3; v++) {
                int id = vertices[t * 3 + v];
                int fan = fanRoot(fans, t * 3 + v);
                if (owner[id] >= 0 && owner[id] != fan)
                    fail(t, "nonmanifold vertex with disconnected incident triangle fans");
                owner[id] = fan;
            }
    }

    private static int fanRoot(int[] fans, int v) {
        while (fans[v] != v) {
            fans[v] = fans[fans[v]];
            v = fans[v];
        }
        return v;
    }

    private int root(int t) {
        while (shell[t] != t) {
            shell[t] = shell[shell[t]];
            t = shell[t];
        }
        return t;
    }

    void finishValidation() {
        double[] volume = new double[shells];
        int[] sample = new int[shells];
        Arrays.fill(sample, -1);
        for (int t = 0; t < shell.length; t++) {
            if (sample[shell[t]] < 0) sample[shell[t]] = t;
            int i = t * 9, o = sample[shell[t]] * 9;
            double ax = points[i] - points[o], ay = points[i + 1] - points[o + 1], az = points[i + 2] - points[o + 2];
            double bx = points[i + 3] - points[o],
                    by = points[i + 4] - points[o + 1],
                    bz = points[i + 5] - points[o + 2];
            double cx = points[i + 6] - points[o],
                    cy = points[i + 7] - points[o + 1],
                    cz = points[i + 8] - points[o + 2];
            volume[shell[t]] += (ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)) / 6;
        }
        for (int s = 0; s < shells; s++) {
            checkInterrupted(sample[s]);
            if (!Double.isFinite(volume[s]) || Math.abs(volume[s]) <= Math.pow(info.weldTolerance(), 3))
                fail(sample[s], "shell has zero/invalid signed volume");
            int i = sample[s] * 9, depth = 0, parent = -1;
            for (int other = 0; other < shells; other++) {
                checkInterrupted(sample[s]);
                if (other == s || !inside(0, points[i], points[i + 1], points[i + 2], other)) continue;
                depth++;
                if (parent < 0 || Math.abs(volume[other]) < Math.abs(volume[parent])) parent = other;
            }
            if (parent >= 0 && Math.signum(volume[parent]) == Math.signum(volume[s]))
                fail(sample[s], "inconsistent nested-shell winding; cavities must reverse their enclosing shell");
            if ((depth & 1) != 0) cavities++;
        }
    }

    boolean inside(double x, double y, double z) {
        return inside(0, x, y, z, -1);
    }

    private boolean inside(int node, double x, double y, double z, int onlyShell) {
        int i = node * 6;
        if (x > bounds[i + 3] || y < bounds[i + 1] || y > bounds[i + 4] || z < bounds[i + 2] || z > bounds[i + 5])
            return false;
        if (count[node] == 0) return inside(left[node], x, y, z, onlyShell) ^ inside(right[node], x, y, z, onlyShell);
        boolean odd = false;
        for (int k = start[node]; k < start[node] + count[node]; k++) {
            int t = order[k];
            if ((onlyShell < 0 || shell[t] == onlyShell) && MeshPredicates.ray(points, t, x, y, z)) odd = !odd;
        }
        return odd;
    }

    boolean surface(double x, double y, double z, double half) {
        return surface(0, x, y, z, half + info.weldTolerance());
    }

    private boolean surface(int node, double x, double y, double z, double h) {
        int i = node * 6;
        if (x + h < bounds[i]
                || x - h > bounds[i + 3]
                || y + h < bounds[i + 1]
                || y - h > bounds[i + 4]
                || z + h < bounds[i + 2]
                || z - h > bounds[i + 5]) return false;
        if (count[node] == 0) return surface(left[node], x, y, z, h) || surface(right[node], x, y, z, h);
        for (int k = start[node]; k < start[node] + count[node]; k++)
            if (MeshPredicates.box(points, order[k], x, y, z, h)) return true;
        return false;
    }

    void validateTriangle(int t, double[] pairA, double[] pairB, Runnable checkpoint) {
        intersections(t, 0, pairA, pairB, checkpoint);
    }

    private void intersections(int t, int node, double[] pairA, double[] pairB, Runnable checkpoint) {
        checkpoint.run();
        int i = node * 6;
        double h = info.weldTolerance();
        for (int a = 0; a < 3; a++)
            if (triangleMax(t, a) + h < bounds[i + a] || triangleMin(t, a) - h > bounds[i + a + 3]) return;
        if (count[node] == 0) {
            intersections(t, left[node], pairA, pairB, checkpoint);
            intersections(t, right[node], pairA, pairB, checkpoint);
            return;
        }
        for (int k = start[node]; k < start[node] + count[node]; k++) {
            int other = order[k];
            if (other <= t) continue;
            int shared = 0;
            for (int a = 0; a < 3; a++)
                for (int b = 0; b < 3; b++) if (vertices[t * 3 + a] == vertices[other * 3 + b]) shared++;
            if (shared == 3) fail(t, "duplicate triangle " + other);
            copyTriangle(t, pairA, shared > 0);
            copyTriangle(other, pairB, shared > 0);
            if (MeshPredicates.triangles(pairA, pairB, shared > 0 ? 0 : h))
                fail(t, "self-intersection or ambiguous shell contact with triangle " + other);
        }
    }

    private void copyTriangle(int t, double[] out, boolean shrink) {
        int base = t * 9;
        double length = 0;
        for (int v = 1; v < 3; v++)
            length = Math.max(
                    length,
                    Math.hypot(
                            Math.hypot(
                                    points[base + v * 3] - points[base], points[base + v * 3 + 1] - points[base + 1]),
                            points[base + v * 3 + 2] - points[base + 2]));
        double fraction = Math.min(1e-7, info.weldTolerance() / length);
        for (int a = 0; a < 3; a++) {
            int i = t * 9 + a;
            double center = points[i] + ((points[i + 3] - points[i]) + (points[i + 6] - points[i])) / 3;
            for (int v = 0; v < 3; v++)
                out[v * 3 + a] =
                        shrink ? points[i + v * 3] + (center - points[i + v * 3]) * fraction : points[i + v * 3];
        }
    }

    private int build(int from, int to) {
        checkInterrupted(order[from]);
        int node = nodes++, i = node * 6;
        for (int a = 0; a < 3; a++) {
            bounds[i + a] = Double.POSITIVE_INFINITY;
            bounds[i + a + 3] = Double.NEGATIVE_INFINITY;
            for (int k = from; k < to; k++) {
                bounds[i + a] = Math.min(bounds[i + a], triangleMin(order[k], a));
                bounds[i + a + 3] = Math.max(bounds[i + a + 3], triangleMax(order[k], a));
            }
        }
        start[node] = from;
        if (to - from <= 8) count[node] = to - from;
        else {
            int axis = 0;
            for (int a = 1; a < 3; a++)
                if (bounds[i + a + 3] - bounds[i + a] > bounds[i + axis + 3] - bounds[i + axis]) axis = a;
            sort(from, to - 1, axis);
            int mid = (from + to) >>> 1;
            left[node] = build(from, mid);
            right[node] = build(mid, to);
        }
        return node;
    }

    private void sort(int lo, int hi, int axis) {
        /// Recurse into the smaller partition to bound the sort stack even for hostile meshes.
        while (lo < hi) {
            checkInterrupted(order[lo]);
            int i = lo, j = hi, pivot = order[(lo + hi) >>> 1];
            while (i <= j) {
                while (compare(order[i], pivot, axis) < 0) i++;
                while (compare(order[j], pivot, axis) > 0) j--;
                if (i <= j) {
                    int v = order[i];
                    order[i++] = order[j];
                    order[j--] = v;
                }
            }
            if (j - lo < hi - i) {
                if (lo < j) sort(lo, j, axis);
                lo = i;
            } else {
                if (i < hi) sort(i, hi, axis);
                hi = j;
            }
        }
    }

    private int compare(int a, int b, int axis) {
        double ca = triangleMin(a, axis) / 2 + triangleMax(a, axis) / 2;
        double cb = triangleMin(b, axis) / 2 + triangleMax(b, axis) / 2;
        int value = Double.compare(ca, cb);
        return value == 0 ? Integer.compare(a, b) : value;
    }

    private double triangleMin(int t, int a) {
        int i = t * 9 + a;
        return Math.min(points[i], Math.min(points[i + 3], points[i + 6]));
    }

    private double triangleMax(int t, int a) {
        int i = t * 9 + a;
        return Math.max(points[i], Math.max(points[i + 3], points[i + 6]));
    }

    private void checkInterrupted(int t) {
        if (Thread.currentThread().isInterrupted()) fail(t, "interrupted during mesh preprocessing");
    }

    private void fail(int t, String message) {
        throw StlReader.error(info.mesh().name() + " (" + info.path() + ")", t, message);
    }
}
