package io.euhedral_execution.benchmarks.cfd.execution;

import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;

/// Driver-owned generation dispatch. Successful return means all terminal results are visible.
public interface ExecutionBackend extends AutoCloseable {
    void prepare(CfdRangeFrame[] ranges);

    void execute(StepContext context);

    void cancel();

    @Override
    void close();
}
