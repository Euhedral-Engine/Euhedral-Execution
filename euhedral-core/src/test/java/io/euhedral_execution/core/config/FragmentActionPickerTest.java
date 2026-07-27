package io.euhedral_execution.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FragmentActionPickerTest {

    @Test
    void zeroVectorCanReenterHaltModeAfterAnActivePolicy() {
        double[] halt = new double[28];
        double[] active = new double[28];
        Arrays.fill(active, 1.0);

        FragmentActionPicker picker = new FragmentActionPicker(halt);
        assertThat(picker.halted()).isTrue();

        picker.setWeights(active);
        assertThat(picker.halted()).isFalse();

        picker.setWeights(halt);
        assertThat(picker.halted()).isTrue();
    }

    @Test
    void negativePoliciesAreNotMistakenForTheHaltSentinel() {
        double[] active = new double[28];
        Arrays.fill(active, -1.0);

        FragmentActionPicker picker = new FragmentActionPicker(active);

        assertThat(picker.halted()).isFalse();
    }
}
