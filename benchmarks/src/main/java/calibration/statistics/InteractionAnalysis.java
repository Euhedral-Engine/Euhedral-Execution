package calibration.statistics;

/// Two-factor interaction analysis: T(A+B) - T(A) - T(B) + T(base).
public final class InteractionAnalysis {

    private InteractionAnalysis() {}

    /// Calculates the two-factor interaction: T(A+B) - T(A) - T(B) + T(base).
    public static double compute(double tBase, double tA, double tB, double tAB) {
        if (!Double.isFinite(tBase) || !Double.isFinite(tA) || !Double.isFinite(tB) || !Double.isFinite(tAB)) {
            throw new IllegalArgumentException("All throughput values must be finite");
        }
        return tAB - tA - tB + tBase;
    }
}
