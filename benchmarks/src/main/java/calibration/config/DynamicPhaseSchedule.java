package calibration.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/// Explicit window schedule using the existing continuous source/body transition mechanism.
public record DynamicPhaseSchedule(List<Phase> phases) {
    public DynamicPhaseSchedule {
        phases = List.copyOf(phases);
        if (phases.size() < 2) {
            throw new IllegalArgumentException("dynamic schedule needs at least two phases");
        }
    }

    public record Phase(String name, int windows, int workUnits, int enabledSources) {
        public Phase {
            if (name == null || name.isBlank() || windows < 1 || workUnits < 0 || enabledSources < 1) {
                throw new IllegalArgumentException("invalid dynamic phase");
            }
        }
    }

    public static DynamicPhaseSchedule parse(String json) {
        try {
            return new ObjectMapper().readValue(json, DynamicPhaseSchedule.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid dynamic phase schedule JSON", e);
        }
    }

    public void validate(int iterations, int sources, CalibrationLifecycleMode lifecycle) {
        if (lifecycle != CalibrationLifecycleMode.CONTINUOUS
                || phases.stream().mapToInt(Phase::windows).sum() != iterations
                || phases.stream().anyMatch(p -> p.enabledSources() > sources)) {
            throw new IllegalArgumentException(
                    "dynamic schedule requires continuous lifecycle, exact windows and available sources");
        }
    }

    public Phase phase(int measurementIndex, boolean warmup) {
        if (warmup) return phases.getFirst();
        if (measurementIndex < 0) throw new IllegalArgumentException("negative measurement index");
        for (Phase phase : phases) {
            if (measurementIndex < phase.windows()) return phase;
            measurementIndex -= phase.windows();
        }
        throw new IllegalArgumentException("measurement index exceeds schedule");
    }
}
