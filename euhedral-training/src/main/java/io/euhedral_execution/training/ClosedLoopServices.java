package io.euhedral_execution.training;

import io.euhedral_execution.training.benchmark.NativeBenchmarkRunPlan;
import io.euhedral_execution.training.data.BenchmarkRunContext;
import io.euhedral_execution.training.learning.*;
import io.euhedral_execution.training.merge.CalibrationPlan;
import java.util.function.BooleanSupplier;

interface ClosedLoopServices {
    CalibrationPlan bootstrapCalibration(DataMerger.CalibrationBootstrapRequest request)
            throws Exception;
    DataMerger.MergeArtifacts merge(DataMerger.MergeRequest request) throws Exception;
    ScenarioTrainingArtifacts train(ScenarioTrainingRequest request) throws Exception;
    ScenarioConditionedModel loadAcceptedModel(java.nio.file.Path modelDirectory,
            String producingDevice) throws Exception;
    BenchmarkRunContext benchmark(NativeBenchmarkRunPlan plan, BooleanSupplier stopRequested)
            throws Exception;
    boolean stopRequested();
    default int activeCoreCount() {
        return io.euhedral_execution.hardware_utils.SystemInfo.getCoreCount();
    }
    default String activeCpuSetHex() {
        return io.euhedral_execution.hardware_utils.SystemInfo.toHexMask(
                io.euhedral_execution.hardware_utils.SystemInfo.getCpuSet());
    }
}
