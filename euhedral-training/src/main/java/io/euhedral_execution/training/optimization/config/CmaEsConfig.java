package io.euhedral_execution.training.optimization.config;

public record CmaEsConfig(boolean enabled, int islands, int generations, int populationSize,
        double initialSigma, int minimumSeedPolicies) {
    public CmaEsConfig {
        if (islands < 1 || generations < 1 || populationSize < 8
                || !Double.isFinite(initialSigma) || initialSigma < 0.005 || initialSigma > 1.0
                || minimumSeedPolicies < 2) {
            throw new IllegalArgumentException("Invalid CMA-ES config");
        }
    }

    public static CmaEsConfig defaults() {
        return new CmaEsConfig(true, 4, 12, 96, 0.20, 10);
    }
}
