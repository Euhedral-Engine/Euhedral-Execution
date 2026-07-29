package io.euhedral_execution.training.data;

public record SourceRatio(int numerator, int denominator) {

    public static SourceRatio of(int sourceCount, int coreCount) {
        if (sourceCount <= 0 || coreCount <= 0) {
            throw new IllegalArgumentException("Counts must be positive");
        }
        int divisor = gcd(sourceCount, coreCount);
        return new SourceRatio(sourceCount / divisor, coreCount / divisor);
    }

    private static int gcd(int left, int right) {
        while (right != 0) {
            int remainder = left % right;
            left = right;
            right = remainder;
        }
        return Math.abs(left);
    }

    public SourceRatio {
        if (numerator <= 0 || denominator <= 0 || gcd(numerator, denominator) != 1) {
            throw new IllegalArgumentException("Ratio must be positive and reduced");
        }
    }

    public double asDouble() {
        return numerator / (double) denominator;
    }
}
