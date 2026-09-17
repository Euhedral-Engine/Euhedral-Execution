package io.euhedral_execution.core.config;

public interface TimingProvider {
    long parkNanos(double contention, double phr, double bodyNanos, long fallback);

    long halfLifeNanos(double contention, double phr, double bodyNanos, long fallback);
}
