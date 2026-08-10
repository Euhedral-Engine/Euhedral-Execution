package io.euhedral_execution.training.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.packaging.enums.TrainingRunPackageStatus;
import org.junit.jupiter.api.Test;

class TrainingRunPackagerTest {
    @Test
    void lifecycleStatusesAreDistinctAndStable() {
        assertThat(TrainingRunPackageStatus.values())
                .containsExactly(
                        TrainingRunPackageStatus.COMPLETE,
                        TrainingRunPackageStatus.PARTIAL_RECOVERABLE,
                        TrainingRunPackageStatus.PARTIAL_TERMINAL);
        assertThat(CheckpointStage.RUN_COMPLETE.name()).isEqualTo("RUN_COMPLETE");
    }
}
