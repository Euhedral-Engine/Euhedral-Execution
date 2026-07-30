package io.euhedral_execution.core.frames;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.hashing.HasherApi;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BenchmarkFrameTest {
    @Test
    void deterministicUnorderedRoutingUsesSuppliedSeed() {
        BenchmarkFrame[] frames = BenchmarkFrame.generate(4, false, 0x1234L, 0xabcL);
        for (int i = 0; i < frames.length; i++) {
            assertThat(frames[i].getIdHash()).isEqualTo(0x1234L);
            assertThat(frames[i].getRoutingHash()).isEqualTo(0x1234L ^ HasherApi.mix(0xabcL + i));
        }
    }

    @Test
    void orderedRoutingRetainsIdentityHash() {
        BenchmarkFrame[] frames = BenchmarkFrame.generate(3, true, 0x5555L, 0xabcL);
        for (BenchmarkFrame frame : frames) {
            assertThat(frame.getIdHash()).isEqualTo(0x5555L);
            assertThat(frame.getRoutingHash()).isEqualTo(0x5555L);
        }
    }

    @Test
    void killSwitchOverloadPreservesConstruction() {
        AtomicBoolean killSwitch = new AtomicBoolean();
        BenchmarkFrame[] frames = BenchmarkFrame.generate(1, false, 7L, 9L, killSwitch);
        assertThat(frames).hasSize(1);
        assertThat(frames[0].getRoutingHash()).isEqualTo(7L ^ HasherApi.mix(9L));
    }
}
