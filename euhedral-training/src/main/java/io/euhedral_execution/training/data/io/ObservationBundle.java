package io.euhedral_execution.training.data.io;

import java.util.List;

import io.euhedral_execution.training.data.BenchmarkObservation;
import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.data.ScheduledPolicy;

public record ObservationBundle(BenchmarkRunContext run, List<ScheduledPolicy> policies,
                                List<BenchmarkObservation> observations) {

    public ObservationBundle {
        policies = List.copyOf(policies);
        observations = List.copyOf(observations);
    }
}
