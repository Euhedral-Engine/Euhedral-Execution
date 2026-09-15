package io.euhedral_execution.benchmarks.cfd.execution;

import io.euhedral_execution.benchmarks.cfd.solver.FlowDiagnostics;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;

/// Driver-owned generation dispatch. Successful return means all terminal results are visible.
public interface ExecutionBackend extends AutoCloseable {
    void prepare(RangePlan plan);

    void execute(StepContext context, FlowDiagnostics diagnostics);

    void cancel();

    long framesPreallocated();

    /// Retained backends never construct frames after prepare.
    default long framesCreatedDuringExecution() {
        return 0;
    }

    default long recyclerMisses() {
        return 0;
    }

    @Override
    void close();
}
