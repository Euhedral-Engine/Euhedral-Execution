package io.euhedral_execution.training.scheduling.data;

import io.euhedral_execution.training.data.BenchmarkParameters;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.scheduling.enums.RunKind;
import java.util.List;

public record ScheduledRun(
        RunKind runKind,
        SourceScenario scenario,
        String benchmarkRunId,
        String candidateCohortId,
        BenchmarkParameters parameters,
        List<ScheduledPolicy> policies) {
    public ScheduledRun {
        policies = List.copyOf(policies);
    }
}
