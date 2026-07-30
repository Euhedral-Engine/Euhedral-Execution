package io.euhedral_execution.training.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.scheduling.data.RotationGroup;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ScenarioRotationTest {
    @Test
    void rotatesAndKeepsEnvironmentCursorsIndependent() {
        var a1 = SourceScenario.of("a", 1, 8);
        var a2 = SourceScenario.of("a", 2, 8);
        var a3 = SourceScenario.of("a", 4, 8);
        var a4 = SourceScenario.of("a", 8, 8);
        var b = SourceScenario.of("b", 1, 8);
        var required = new TreeSet<>(List.of(a1, a2, a3, a4, b));
        TreeMap<RotationGroup, Integer> cursors = new TreeMap<>();
        cursors.put(new RotationGroup("a", 8), 3);
        cursors.put(new RotationGroup("b", 8), 0);

        List<SourceScenario> selected = ScenarioRotation.select(required, cursors,
                "a", 8, 2);
        assertThat(selected).containsExactly(a4, a1);
        var advanced = ScenarioRotation.advance(required, cursors, selected);
        assertThat(advanced.get(new RotationGroup("a", 8))).isEqualTo(1);
        assertThat(advanced.get(new RotationGroup("b", 8))).isZero();
        assertThat(cursors.get(new RotationGroup("a", 8))).isEqualTo(3);
    }

    @Test
    void rejectsAbsentExactGroup() {
        assertThatThrownBy(() -> ScenarioRotation.select(
                new TreeSet<>(List.of(SourceScenario.of("a", 1, 8))), new TreeMap<>(),
                "a", 4, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
