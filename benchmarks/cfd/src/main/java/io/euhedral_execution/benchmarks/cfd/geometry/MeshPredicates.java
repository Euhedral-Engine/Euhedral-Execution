package io.euhedral_execution.benchmarks.cfd.geometry;

import java.math.BigDecimal;

/// Scalar separating-axis and half-open projected-ray predicates, used only during preprocessing.
final class MeshPredicates {
    private MeshPredicates() {}

    static boolean box(double[] p, int t, double x, double y, double z, double half) {
        int i = t * 9;
        if (separatesBox(p, i, x, y, z, half, 1, 0, 0)
                || separatesBox(p, i, x, y, z, half, 0, 1, 0)
                || separatesBox(p, i, x, y, z, half, 0, 0, 1)) return false;
        double ax = p[i + 3] - p[i], ay = p[i + 4] - p[i + 1], az = p[i + 5] - p[i + 2];
        double bx = p[i + 6] - p[i], by = p[i + 7] - p[i + 1], bz = p[i + 8] - p[i + 2];
        if (separatesBox(p, i, x, y, z, half, ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)) return false;
        for (int e = 0; e < 3; e++) {
            int a = i + 3 * e, b = i + 3 * ((e + 1) % 3);
            double ex = p[b] - p[a], ey = p[b + 1] - p[a + 1], ez = p[b + 2] - p[a + 2];
            if (separatesBox(p, i, x, y, z, half, 0, ez, -ey)
                    || separatesBox(p, i, x, y, z, half, -ez, 0, ex)
                    || separatesBox(p, i, x, y, z, half, ey, -ex, 0)) return false;
        }
        return true;
    }

    private static boolean separatesBox(
            double[] p, int i, double x, double y, double z, double h, double ax, double ay, double az) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int v = 0; v < 3; v++) {
            int j = i + 3 * v;
            double d = (p[j] - x) * ax + (p[j + 1] - y) * ay + (p[j + 2] - z) * az;
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        double r = h * (Math.abs(ax) + Math.abs(ay) + Math.abs(az));
        return min > r || max < -r;
    }

    /// A +X ray uses a top-left ownership rule in the YZ projection: exactly one incident
    /// triangle owns a shared edge/vertex. Parallel faces contribute no crossing.
    static boolean ray(double[] p, int t, double x, double y, double z) {
        int a = t * 9, b = a + 3, c = a + 6;
        double area = orient(p[a + 1], p[a + 2], p[b + 1], p[b + 2], p[c + 1], p[c + 2]);
        if (area == 0) return false;
        if (area < 0) {
            int swap = b;
            b = c;
            c = swap;
            area = -area;
        }
        double wa = orient(p[b + 1], p[b + 2], p[c + 1], p[c + 2], y, z);
        double wb = orient(p[c + 1], p[c + 2], p[a + 1], p[a + 2], y, z);
        double wc = orient(p[a + 1], p[a + 2], p[b + 1], p[b + 2], y, z);
        if (!edge(p, b, c, wa) || !edge(p, c, a, wb) || !edge(p, a, b, wc)) return false;
        double hit = p[a] + (wb / area) * (p[b] - p[a]) + (wc / area) * (p[c] - p[a]);
        return hit > x;
    }

    private static boolean edge(double[] p, int a, int b, double sign) {
        if (sign != 0) return sign > 0;
        double dy = p[b + 2] - p[a + 2], dx = p[b + 1] - p[a + 1];
        return dy > 0 || (dy == 0 && dx < 0);
    }

    private static double orient(double ax, double ay, double bx, double by, double cx, double cy) {
        if ((bx == ax || cy == ay) && (by == ay || cx == ax)) return 0;
        double left = (bx - ax) * (cy - ay), right = (by - ay) * (cx - ax), value = left - right;
        if (Math.abs(value) > (Math.abs(left) + Math.abs(right)) * 1e-14) return value;
        /// Exact fallback for cancellation at shared edges. Allocations are confined to setup.
        var a = new BigDecimal(ax);
        var b = new BigDecimal(ay);
        var exact = new BigDecimal(bx)
                .subtract(a)
                .multiply(new BigDecimal(cy).subtract(b))
                .subtract(new BigDecimal(by).subtract(b).multiply(new BigDecimal(cx).subtract(a)));
        double result = exact.doubleValue();
        return result == 0 && exact.signum() != 0 ? Math.copySign(Double.MIN_VALUE, exact.signum()) : result;
    }

    /// SAT also tests normal-cross-edge axes, which separate coplanar triangles.
    static boolean triangles(double[] a, double[] b, double tolerance) {
        for (int face = 0; face < 2; face++) {
            double[] p = face == 0 ? a : b;
            double ux = p[3] - p[0], uy = p[4] - p[1], uz = p[5] - p[2];
            double vx = p[6] - p[0], vy = p[7] - p[1], vz = p[8] - p[2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            if (separatesTriangles(a, b, nx, ny, nz, tolerance)) return false;
            for (int e = 0; e < 3; e++) {
                int i = 3 * e, j = 3 * ((e + 1) % 3);
                double ex = p[j] - p[i], ey = p[j + 1] - p[i + 1], ez = p[j + 2] - p[i + 2];
                if (separatesTriangles(a, b, ny * ez - nz * ey, nz * ex - nx * ez, nx * ey - ny * ex, tolerance))
                    return false;
            }
        }
        for (int e = 0; e < 3; e++)
            for (int f = 0; f < 3; f++) {
                int i = 3 * e, j = 3 * ((e + 1) % 3), k = 3 * f, l = 3 * ((f + 1) % 3);
                double ex = a[j] - a[i], ey = a[j + 1] - a[i + 1], ez = a[j + 2] - a[i + 2];
                double fx = b[l] - b[k], fy = b[l + 1] - b[k + 1], fz = b[l + 2] - b[k + 2];
                if (separatesTriangles(a, b, ey * fz - ez * fy, ez * fx - ex * fz, ex * fy - ey * fx, tolerance))
                    return false;
            }
        return true;
    }

    private static boolean separatesTriangles(double[] a, double[] b, double x, double y, double z, double tolerance) {
        double amin = Double.POSITIVE_INFINITY, amax = Double.NEGATIVE_INFINITY;
        double bmin = Double.POSITIVE_INFINITY, bmax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 9; i += 3) {
            double av = (a[i] - a[0]) * x + (a[i + 1] - a[1]) * y + (a[i + 2] - a[2]) * z;
            double bv = (b[i] - a[0]) * x + (b[i + 1] - a[1]) * y + (b[i + 2] - a[2]) * z;
            amin = Math.min(amin, av);
            amax = Math.max(amax, av);
            bmin = Math.min(bmin, bv);
            bmax = Math.max(bmax, bv);
        }
        double margin = tolerance * (Math.abs(x) + Math.abs(y) + Math.abs(z));
        return amin > bmax + margin || bmin > amax + margin;
    }
}
