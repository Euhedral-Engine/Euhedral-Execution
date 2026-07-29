package io.euhedral_execution.training.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.scheduling.data.IterationSchedule;
import io.euhedral_execution.training.scheduling.fixtures.SchedulingFixtures;
import io.euhedral_execution.training.scheduling.io.ScheduleCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScheduleCodecTest {
    @TempDir
    Path temp;

    @Test
    void roundTripsStrictBootstrapScheduleAndProducesIdenticalBytes() throws Exception {
        BenchmarkExecutionConfig config = new BenchmarkExecutionConfig(2, 100, 50, 8,
                1_000, false);
        IterationSchedule schedule = BootstrapScheduler.create("training",
                SchedulingFixtures.S1, List.of(SchedulingFixtures.policy(1),
                        SchedulingFixtures.policy(2), SchedulingFixtures.policy(3)),
                91L, 0, "0".repeat(40), false, "f", config);
        Path first = ScheduleCodec.write(temp.resolve("first"), schedule);
        Path second = ScheduleCodec.write(temp.resolve("second"), schedule);
        IterationSchedule read = ScheduleCodec.read(first,
                new TreeSet<>(List.of(SchedulingFixtures.S1)), "training", 91L,
                "0".repeat(40), false, config);

        assertThat(read.trainingRunId()).isEqualTo("training");
        assertThat(read.runs()).isEqualTo(schedule.runs());
        for (String file : List.of("runs.csv", "policies.csv", "predictions.csv",
                "budget-report.csv", "carry-admissions.csv", "COMPLETE")) {
            assertThat(Files.readAllBytes(first.resolve(file)))
                    .containsExactly(Files.readAllBytes(second.resolve(file)));
        }
    }

    @Test
    void rejectsChangedIdentityAndUnexpectedFiles() throws Exception {
        BenchmarkExecutionConfig config = new BenchmarkExecutionConfig(1, 100, 50, 8,
                1_000, false);
        IterationSchedule schedule = BootstrapScheduler.create("training",
                SchedulingFixtures.S1, List.of(SchedulingFixtures.policy(1),
                        SchedulingFixtures.policy(2)), 91L, 0, "0".repeat(40), false, "f", config);
        Path directory = ScheduleCodec.write(temp.resolve("schedule"), schedule);
        Files.writeString(directory.resolve("unexpected"), "x");
        assertThatThrownBy(() -> ScheduleCodec.read(directory,
                new TreeSet<>(List.of(SchedulingFixtures.S1)), "training", 91L,
                "0".repeat(40), false, config)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateSchedulePositions() throws Exception {
        BenchmarkExecutionConfig config = new BenchmarkExecutionConfig(1, 100, 50, 8,
                1_000, false);
        IterationSchedule schedule = BootstrapScheduler.create("training",
                SchedulingFixtures.S1, List.of(SchedulingFixtures.policy(1),
                        SchedulingFixtures.policy(2), SchedulingFixtures.policy(3)),
                91L, 0, "0".repeat(40), false, "f", config);
        Path directory = ScheduleCodec.write(temp.resolve("duplicate-position"), schedule);
        List<List<String>> rows = CanonicalCsv.read(directory.resolve("policies.csv"));
        var duplicate = new java.util.ArrayList<>(rows.get(2));
        duplicate.set(3, rows.get(1).get(3));
        StringBuilder changed = new StringBuilder(CanonicalCsv.row(rows.getFirst()))
                .append(CanonicalCsv.row(rows.get(1)))
                .append(CanonicalCsv.row(duplicate));
        for (int index = 3; index < rows.size(); index++) {
            changed.append(CanonicalCsv.row(rows.get(index)));
        }
        Files.writeString(directory.resolve("policies.csv"), changed);
        assertThatThrownBy(() -> ScheduleCodec.read(directory,
                new TreeSet<>(List.of(SchedulingFixtures.S1)), "training", 91L,
                "0".repeat(40), false, config))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
