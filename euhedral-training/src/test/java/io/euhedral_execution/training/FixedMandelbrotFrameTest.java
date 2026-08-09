package io.euhedral_execution.training;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.hashing.HasherApi;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FixedMandelbrotFrameTest {

    @Test
    void repeatedGenerationAndExecutionProducesIdenticalResults() {
        FixedMandelbrotFrame[] first = FixedMandelbrotFrame.generate(8, false, 0x1234L, 0xabcL);
        FixedMandelbrotFrame[] second = FixedMandelbrotFrame.generate(8, false, 0x1234L, 0xabcL);

        for (int i = 0; i < first.length; i++) {
            first[i].execute();
            second[i].execute();
            assertThat(first[i].completedIterations()).isEqualTo(second[i].completedIterations());
            assertThat(first[i].resultChecksum()).isEqualTo(second[i].resultChecksum());
        }
    }

    @Test
    void fixedCoordinatePaletteProducesDifferentWorkCosts() {
        FixedMandelbrotFrame[] frames = FixedMandelbrotFrame.generate(8, true, 7L, 9L);

        Arrays.stream(frames).forEach(FixedMandelbrotFrame::execute);

        assertThat(Arrays.stream(frames)
                        .mapToInt(FixedMandelbrotFrame::completedIterations)
                        .distinct()
                        .count())
                .isGreaterThan(3);
        assertThat(Arrays.stream(frames)
                        .mapToInt(FixedMandelbrotFrame::completedIterations)
                        .min()
                        .orElseThrow())
                .isLessThan(20);
        assertThat(Arrays.stream(frames)
                        .mapToInt(FixedMandelbrotFrame::completedIterations)
                        .max())
                .hasValue(20_000);
    }

    @Test
    void routingMatchesExistingBenchmarkContract() {
        FixedMandelbrotFrame[] unordered = FixedMandelbrotFrame.generate(4, false, 0x5555L, 0xabcL);
        FixedMandelbrotFrame[] ordered = FixedMandelbrotFrame.generate(4, true, 0x5555L, 0xabcL);

        for (int i = 0; i < unordered.length; i++) {
            assertThat(unordered[i].getIdHash()).isEqualTo(0x5555L);
            assertThat(unordered[i].getRoutingHash()).isEqualTo(0x5555L ^ HasherApi.mix(0xabcL + i));
            assertThat(ordered[i].getIdHash()).isEqualTo(0x5555L);
            assertThat(ordered[i].getRoutingHash()).isEqualTo(0x5555L);
        }
    }
}
