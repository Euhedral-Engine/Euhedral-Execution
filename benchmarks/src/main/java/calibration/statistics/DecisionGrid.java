package calibration.statistics;

/// Shape of the simplified decision tree: one binary contention split followed by five body-cost outcomes.
public final class DecisionGrid {
    public static final int CONTENTION_OUTCOMES = 2;
    public static final int BODY_OUTCOMES = 5;
    public static final int TOTAL_STATES = CONTENTION_OUTCOMES * BODY_OUTCOMES;

    private DecisionGrid() {}
}
