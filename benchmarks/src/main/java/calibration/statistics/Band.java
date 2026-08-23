package calibration.statistics;

/// Fixed five-element body-cost coordinate bands.
public enum Band {
    XS(0),
    S(1),
    M(2),
    H(3),
    XH(4);

    private final int index;

    Band(int index) {
        this.index = index;
    }

    public int index() {
        return index;
    }

    public static Band fromIndex(int index) {
        return switch (index) {
            case 0 -> XS;
            case 1 -> S;
            case 2 -> M;
            case 3 -> H;
            case 4 -> XH;
            default -> throw new IllegalArgumentException("Invalid band index: " + index + " (expected 0..4)");
        };
    }
}
