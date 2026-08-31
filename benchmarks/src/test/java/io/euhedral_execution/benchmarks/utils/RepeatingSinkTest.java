package io.euhedral_execution.benchmarks.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.benchmarks.frames.NoOpFrame;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepeatingSinkTest {

    @Test
    void sourceRingContinuesAcrossObservationalWindowBoundaryWithoutReset() {
        RepeatingSink sink =
                new RepeatingSink(new AbstractFrame[] {new NoOpFrame(11L, null), new NoOpFrame(22L, null)});
        LatticeSource source = sink.getDelegate();
        source.addDownstream(new NoOpReceiver());
        List<Long> hashes = new ArrayList<>();

        assertEquals(3L, source.pull(frame -> hashes.add(frame.getIdHash()), frame -> false, 3L));
        // An observational CONTINUOUS boundary performs no source operation.
        assertEquals(3L, source.pull(frame -> hashes.add(frame.getIdHash()), frame -> false, 3L));

        assertEquals(List.of(11L, 22L, 11L, 22L, 11L, 22L), hashes);
    }

    @Test
    void pausedSourceRemainsConnectedAndResumesItsExistingRing() {
        RepeatingSink sink =
                new RepeatingSink(new AbstractFrame[] {new NoOpFrame(11L, null), new NoOpFrame(22L, null)});
        LatticeSource source = sink.getDelegate();
        source.addDownstream(new NoOpReceiver());
        List<Long> hashes = new ArrayList<>();

        assertEquals(1L, source.pull(frame -> hashes.add(frame.getIdHash()), frame -> false, 1L));
        sink.setEnabled(false);
        assertFalse(sink.isEnabled());
        assertEquals(0L, source.pull(frame -> hashes.add(frame.getIdHash()), frame -> false, 2L));
        sink.setEnabled(true);
        assertTrue(sink.isEnabled());
        assertEquals(1L, source.pull(frame -> hashes.add(frame.getIdHash()), frame -> false, 1L));
        assertEquals(List.of(11L, 22L), hashes);
    }

    private static final class NoOpReceiver implements LatticeReceiver {
        @Override
        public void push(AbstractFrame frame) {}

        @Override
        public void onComplete() {}

        @Override
        public void onError(Throwable e) {}

        @Override
        public void addUpstream(LatticeSource upstream) {}
    }
}
