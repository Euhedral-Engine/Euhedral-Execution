package euhedral.atomics.helpers;

public final class DoubleInterfaces {
    @FunctionalInterface
    public interface DoubleUnaryOperator {
        double applyAsDouble(double curr);
    }

    @FunctionalInterface
    public interface DoubleBinaryOperator {
        double applyAsDouble(double prev, double curr);
    }
}
