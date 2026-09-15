package io.euhedral_execution.benchmarks.cfd.execution;

import io.euhedral_execution.benchmarks.cfd.solver.FlowDiagnostics;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;

/// Driver-owned generation dispatch. Successful return means all terminal results are visible.
public interface ExecutionBackend extends AutoCloseable {
    void prepare(RangePlan plan);

    void execute(StepContext context, FlowDiagnostics diagnostics);

    void cancel();

    @Override
    void close();
}
