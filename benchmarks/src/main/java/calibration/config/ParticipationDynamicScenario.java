package calibration.config;

/// Throughput-only phase changes applied between JMH iterations without rebuilding the lattice.
public enum ParticipationDynamicScenario {
    NONE(new Phase(0, -1), new Phase(0, -1)),
    BODY_LOW_TO_HIGH(new Phase(0, -1), new Phase(768, -1)),
    BODY_HIGH_TO_LOW(new Phase(768, -1), new Phase(0, -1)),
    SOURCES_LOW_TO_HIGH(new Phase(172, 1), new Phase(172, -1)),
    SOURCES_HIGH_TO_LOW(new Phase(172, -1), new Phase(172, 1)),
    COMBINED(new Phase(0, 1), new Phase(768, -1));

    private final Phase first;
    private final Phase second;

    ParticipationDynamicScenario(Phase first, Phase second) {
        this.first = first;
        this.second = second;
    }

    public Phase phase(int iteration, int iterationCount) {
        if (iteration < 0 || iterationCount <= 0 || iteration >= iterationCount) {
            throw new IllegalArgumentException("invalid dynamic phase iteration");
        }
        return iteration < (iterationCount + 1) / 2 ? this.first : this.second;
    }

    public record Phase(int workUnits, int enabledSources) {}
}
