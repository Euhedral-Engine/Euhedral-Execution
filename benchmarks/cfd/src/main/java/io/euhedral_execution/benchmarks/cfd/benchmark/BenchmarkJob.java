package io.euhedral_execution.benchmarks.cfd.benchmark;

import io.euhedral_execution.benchmarks.cfd.execution.BackendOptions;
import io.euhedral_execution.benchmarks.cfd.execution.WorkerBudget;

/// One immutable fork contract; written and checked outside measurement.
public record BenchmarkJob(
        String configuration,
        String directory,
        String reference,
        String referenceSha256,
        String validationReport,
        String validationSha256,
        String validationCase,
        BenchmarkSuite.ValidationScope validationScope,
        String caseIdentity,
        String numericalIdentity,
        String artifactIdentity,
        BackendOptions options,
        WorkerBudget budget,
        long preSteps,
        long stepsPerInvocation,
        int warmupIterations,
        int measurementIterations,
        long iterationMillis,
        long processDeadlineMillis) {
    public BenchmarkJob {
        BenchmarkSuite.require(
                configuration != null
                        && directory != null
                        && reference != null
                        && validationReport != null
                        && validationSha256 != null
                        && validationCase != null
                        && validationScope != null
                        && caseIdentity != null
                        && numericalIdentity != null
                        && artifactIdentity != null
                        && options != null
                        && budget != null,
                "incomplete fork contract");
        BenchmarkSuite.require(
                preSteps >= 0 && stepsPerInvocation > 0 && preSteps <= Long.MAX_VALUE - stepsPerInvocation,
                "invalid fork work");
        BenchmarkSuite.require(
                warmupIterations > 0 && measurementIterations > 0 && iterationMillis > 0 && processDeadlineMillis > 0,
                "invalid fork timing");
    }
}
