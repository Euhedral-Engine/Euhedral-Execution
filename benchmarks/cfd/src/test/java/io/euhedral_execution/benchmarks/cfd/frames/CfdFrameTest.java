package io.euhedral_execution.benchmarks.cfd.frames;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import io.euhedral_execution.benchmarks.cfd.solver.D3Q19;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.impl.FrameFactory;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class CfdFrameTest {
    private static ControlPlaneLattice lattice;
    private QueueIngestSink sink;

    @BeforeAll
    static void startRuntime() {
        lattice = CfdTestRuntime.singleWorker();
    }

    @AfterAll
    static void closeRuntime() {
        lattice.close();
    }

    @BeforeEach
    void attachSource() {
        sink = new QueueIngestSink();
        lattice.addUpstream(sink);
    }

    @AfterEach
    void detachSource() {
        sink.complete();
        awaitDrained();
    }

    private static void awaitDrained() {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!lattice.isDrained()) {
            if (System.nanoTime() - deadline >= 0) fail("runtime did not become quiescent");
            LockSupport.parkNanos(10_000);
        }
    }

    private static void awaitTerminal(CfdFrame frame) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!frame.isDone()) {
            if (System.nanoTime() - deadline >= 0) fail("missing terminal publication");
            LockSupport.parkNanos(10_000);
        }
    }

    private void submit(CfdFrame frame) {
        assertTrue(sink.offer(frame));
        awaitTerminal(frame);
        awaitDrained();
    }

    private static void awaitRecycled(FrameManager<?, ?> manager, CfdFrame frame) {
        /// Terminal status publishes results before recycler enqueue. Observe the actual queue entry;
        /// a drained lattice does not establish that this frame is available to getOrCreate yet.
        /// These tests have one pooled frame and this thread is the only recycler consumer.
        var queue = manager.getRecycleQueue();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (queue.peek() == null) {
            if (Thread.currentThread().isInterrupted()) fail("interrupted while waiting for frame recycling");
            if (System.nanoTime() - deadline >= 0) fail("frame was not returned to its recycler");
            LockSupport.parkNanos(10_000);
        }
        assertSame(frame, queue.peek(), "the completed frame must be available for reuse");
    }

    private record Fields(GridShape shape, double[][] current, double[][] next) {}

    private Fields fields() throws Exception {
        var configuration = config();
        var shape = configuration.config().grid();
        double[][] current = new double[19][(int) shape.cellCount()];
        double[][] next = new double[19][(int) shape.cellCount()];
        var physics = configuration.physics();
        var velocity = physics.initialVelocity();
        for (int q = 0; q < 19; q++)
            Arrays.fill(
                    current[q],
                    D3Q19.equilibrium(q, physics.densityReference(), velocity.x(), velocity.y(), velocity.z()));
        return new Fields(shape, current, next);
    }

    private CfdConfiguration config() throws Exception {
        return ConfigLoader.load(Path.of("scenes/periodic-smoke.json"));
    }

    private StepContext context(Fields state, double[][] current, double[][] next, long step) {
        return new StepContext(state.shape(), current, next, 1.25, step, 0, Long.MAX_VALUE, () -> 0);
    }

    private static void replaceWhole(CfdRangeFrame frame, StepContext context) {
        var shape = context.shape();
        frame.replace(context, 0, shape.nx(), 0, shape.ny(), 0, shape.nz());
    }

    @Test
    void generationAcknowledgmentFollowsRecyclingAndAllowsImmediateReuse() throws Exception {
        var manager = new FrameManager<StepContext, CfdRangeFrame>(1, 0);
        var frame = new CfdRangeFrame(1, manager);
        var fields = fields();
        var context = context(fields, fields.current(), fields.next(), 1);
        var acknowledgments = new AtomicInteger();
        frame.completion(new CfdRangeFrame.Completion() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public void complete(CfdRangeFrame completed, RuntimeException failure) {
                assertSame(frame, completed);
                assertNull(failure);
                assertNull(manager.get(0), "result publication must precede recycling");
            }

            @Override
            public void recycled() {
                assertSame(frame, manager.get(0), "generation acknowledgment must follow enqueue");
                replaceWhole(frame, context);
                acknowledgments.incrementAndGet();
            }
        });
        replaceWhole(frame, context);
        for (int i = 0; i < 100; i++) {
            frame.execute();
            frame.doFinally();
            assertEquals(CfdFrame.Status.READY, frame.status());
        }
        assertEquals(100, acknowledgments.get());
    }

    @Test
    void rangePublishesCompletionOnlyFromItsTerminalHook() throws Exception {
        var state = fields();
        var frame = new CfdRangeFrame(1, null);
        double[][] current = state.current(), next = state.next();
        replaceWhole(frame, context(state, current, next, 1));
        assertThrows(IllegalStateException.class, () -> replaceWhole(frame, context(state, current, next, 1)));
        frame.execute();
        assertEquals(CfdFrame.Status.EXECUTING, frame.status());
        assertFalse(frame.isDone());
        assertThrows(IllegalStateException.class, frame::execute);
        assertThrows(IllegalStateException.class, () -> replaceWhole(frame, context(state, current, next, 2)));
        assertThrows(IllegalStateException.class, frame::requireSuccess);
        frame.doFinally();
        frame.requireSuccess();
        frame.doFinally();
        assertSame(current, state.current());
        replaceWhole(frame, context(state, next, current, 2));
        submit(frame);
        frame.requireSuccess();
    }

    @Test
    void cancelledRangesNeverAcknowledgeSuccess() throws Exception {
        var state = fields();
        var context = context(state, state.current(), state.next(), 1);
        var beforeExecution = new CfdRangeFrame(1, null);
        replaceWhole(beforeExecution, context);
        beforeExecution.kill();
        submit(beforeExecution);
        assertEquals(CfdFrame.Status.CANCELLED, beforeExecution.status());
        assertThrows(SimulationException.class, beforeExecution::requireSuccess);
        assertEquals(0, state.next()[0][0]);
        var afterBody = new CfdRangeFrame(2, null);
        replaceWhole(afterBody, context);
        afterBody.execute();
        afterBody.kill();
        afterBody.doFinally();
        assertEquals(CfdFrame.Status.CANCELLED, afterBody.status());
        replaceWhole(afterBody, context);
        assertTrue(afterBody.isAlive());
        submit(afterBody);
        afterBody.requireSuccess();
    }

    @Test
    void exceptionAndStructuredCancellationUseTheActualExecutorTerminal() {
        var cancelled = new ProbeFrame(true, null);
        submit(cancelled);
        assertEquals(CfdFrame.Status.CANCELLED, cancelled.status());
        assertEquals(0, cancelled.publications.get());
        var error = new IllegalArgumentException("injected execution failure");
        var failed = new ProbeFrame(false, error);
        submit(failed);
        assertEquals(CfdFrame.Status.FAILED, failed.status());
        assertSame(error, assertThrows(IllegalArgumentException.class, failed::requireSuccess));
        assertEquals(0, failed.publications.get());
        failed.doFinally();
        failed.doFinallyWithError(new IllegalStateException("duplicate terminal"));
        assertSame(error, assertThrows(IllegalArgumentException.class, failed::requireSuccess));
    }

    @Test
    void disjointRangeFramesCanExecuteTheSameGenerationConcurrently() throws Exception {
        var state = fields();
        for (int q = 0; q < 19; q++)
            for (int i = 0; i < state.current()[q].length; i++) state.current()[q][i] *= 1 + 0.001 * Math.sin(i + q);
        double[][] before = Arrays.stream(state.current()).map(double[]::clone).toArray(double[][]::new);
        var context = context(state, state.current(), state.next(), 1);
        var first = new CfdRangeFrame(1, null);
        var second = new CfdRangeFrame(2, null);
        first.replace(context, 0, 5, 0, 10, 0, 8);
        second.replace(context, 5, 12, 0, 10, 0, 8);
        first.randomizeHash(123);
        second.randomizeHash(456);
        assertTrue(sink.offer(first));
        assertTrue(sink.offer(second));
        awaitTerminal(first);
        awaitTerminal(second);
        first.requireSuccess();
        second.requireSuccess();
        double[][] expected = new double[19][(int) state.shape().cellCount()];
        var whole = new CfdRangeFrame(3, null);
        replaceWhole(whole, context(state, before, expected, 1));
        submit(whole);
        whole.requireSuccess();
        for (int q = 0; q < 19; q++) {
            assertArrayEquals(expected[q], state.next()[q], 0);
            assertArrayEquals(before[q], state.current()[q], 0);
        }
    }

    @Test
    void terminalPublicationMakesRangeWritesVisibleToTheSubmittingThread() throws Exception {
        var state = fields();
        var frame = new CfdRangeFrame(100, null);
        replaceWhole(frame, context(state, state.current(), state.next(), 1));
        assertTrue(sink.offer(frame));
        awaitTerminal(frame);
        /// Reads acquire the terminal publication from the lattice worker.
        frame.requireSuccess();
        for (int q = 0; q < 19; q++) assertArrayEquals(state.current()[q], state.next()[q], 1e-15);
    }

    @Test
    void managerReusesTheFrameWithNewContextAndAllSixBounds() throws Exception {
        var state = fields();
        var creations = new AtomicInteger();
        int[] bounds = {0, 2, 0, 3, 0, 4};
        long password = 73;
        var manager = new FrameManager<StepContext, CfdRangeFrame>(8, password);
        try {
            FrameFactory.FrameReplace<StepContext, CfdRangeFrame> replace = (context, frame) ->
                    frame.replace(context, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
            manager.setFactory(new FrameFactory<>(
                    (idHash, context) -> {
                        creations.incrementAndGet();
                        var frame = new CfdRangeFrame(idHash, manager);
                        replace.replace(context, frame);
                        frame.randomizeHash(123);
                        return frame;
                    },
                    replace));
            var firstContext = context(state, state.current(), state.next(), 1);
            var first = manager.getOrCreate(firstContext, password);
            assertNull(manager.get(password));
            submit(first);
            awaitRecycled(manager, first);
            first.requireSuccess();
            double[][] firstResult =
                    Arrays.stream(state.next()).map(double[]::clone).toArray(double[][]::new);

            int[] nextBounds = {1, 5, 2, 7, 3, 8};
            System.arraycopy(nextBounds, 0, bounds, 0, 6);
            double[][] current =
                    Arrays.stream(state.current()).map(double[]::clone).toArray(double[][]::new);
            double[][] next = new double[19][(int) state.shape().cellCount()];
            for (int q = 0; q < 19; q++) {
                for (int cell = 0; cell < current[q].length; cell++) current[q][cell] *= 1.01;
                Arrays.fill(next[q], -999);
            }
            var reused = manager.getOrCreate(context(state, current, next, 2), password);
            assertSame(first, reused);
            assertEquals(1, creations.get());
            assertFalse(reused.isOrdered());
            assertNull(manager.get(password));
            submit(reused);
            awaitRecycled(manager, reused);
            reused.requireSuccess();
            for (int z = 0; z < 8; z++)
                for (int y = 0; y < 10; y++)
                    for (int x = 0; x < 12; x++) {
                        boolean owned = x >= 1 && x < 5 && y >= 2 && y < 7 && z >= 3;
                        int cell = x + 12 * (y + 10 * z);
                        for (int q = 0; q < 19; q++) {
                            assertEquals(owned ? current[q][cell] : -999, next[q][cell], 1e-15);
                            assertEquals(firstResult[q][cell], state.next()[q][cell], 0);
                        }
                    }
        } finally {
            manager.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "cancelBefore", "cancelAfterBody", "failure"})
    void managerRecyclesExactlyOnceAndReplacementResetsTerminalState(String outcome) throws Exception {
        var state = fields();
        long password = 91;
        var manager = new FrameManager<StepContext, CfdRangeFrame>(8, password);
        try {
            manager.setFactory(new FrameFactory<>(
                    (idHash, context) -> {
                        var frame = new CfdRangeFrame(idHash, manager);
                        replaceWhole(frame, context);
                        return frame;
                    },
                    (context, frame) -> replaceWhole(frame, context)));
            var context = context(state, state.current(), state.next(), 1);
            var frame = manager.getOrCreate(context, password);
            double rest = state.current()[0][0];
            if (outcome.equals("cancelBefore")) frame.kill();
            if (outcome.equals("failure")) state.current()[0][0] = Double.NaN;
            if (outcome.equals("cancelAfterBody")) {
                frame.execute();
                frame.kill();
                frame.doFinally();
            } else submit(frame);
            awaitRecycled(manager, frame);
            if (outcome.equals("success")) frame.requireSuccess();
            else {
                assertThrows(SimulationException.class, frame::requireSuccess);
                assertEquals(
                        outcome.equals("failure") ? CfdFrame.Status.FAILED : CfdFrame.Status.CANCELLED, frame.status());
            }
            frame.doFinally();
            frame.doFinallyWithError(new IllegalStateException("duplicate terminal"));
            state.current()[0][0] = rest;
            var reused = manager.getOrCreate(context(state, state.current(), state.next(), 2), password);
            assertSame(frame, reused);
            assertNull(manager.get(password), "duplicate completion must not enqueue the frame twice");
            assertEquals(CfdFrame.Status.READY, reused.status());
            assertTrue(reused.isAlive());
            assertThrows(IllegalStateException.class, reused::requireSuccess);
            submit(reused);
            awaitRecycled(manager, reused);
            reused.requireSuccess();
            for (int q = 0; q < 19; q++) assertArrayEquals(state.current()[q], state.next()[q], 1e-15);
        } finally {
            manager.close();
        }
    }

    private static final class ProbeFrame extends CfdFrame {
        private final boolean cancel;
        private final RuntimeException error;
        private final AtomicInteger publications = new AtomicInteger();

        ProbeFrame(boolean cancel, RuntimeException error) {
            super(200, null);
            this.cancel = cancel;
            this.error = error;
            beginPreparation();
            ready();
        }

        @Override
        protected void executeBody() {
            if (cancel) throwCancelSignal();
            if (error != null) throw error;
        }

        @Override
        protected void publishSuccess() {
            publications.incrementAndGet();
        }

        @Override
        protected long generation() {
            return 1;
        }
    }
}
