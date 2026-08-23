package calibration.statistics;

/// Displacement vector for a single source cell in the 2x5 decision surface.
public record VectorCell(
        int contentionBand,
        int bodyBand,
        long transitionCount,
        double meanDeltaContention,
        double meanDeltaBody,
        double magnitude) {

    public boolean hasVector() {
        return transitionCount > 0L;
    }

    public static VectorCell empty(int contentionBand, int bodyBand) {
        return new VectorCell(contentionBand, bodyBand, 0L, Double.NaN, Double.NaN, Double.NaN);
    }

    public String toTsvRow() {
        return transitionCount + "\t" + meanDeltaContention + "\t" + meanDeltaBody + "\t" + magnitude;
    }
}
