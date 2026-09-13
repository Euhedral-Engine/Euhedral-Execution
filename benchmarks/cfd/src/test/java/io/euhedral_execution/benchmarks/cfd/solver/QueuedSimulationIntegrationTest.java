package io.euhedral_execution.benchmarks.cfd.solver;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class QueuedSimulationIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queuedWorkMustFinishBeforePublicationAndCanExpireWhileWaiting(boolean expire) throws Exception {
        var lattice = ControlPlaneLattice.getOrCreate();
        var blockers = new QueueIngestSink();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queued = new CountDownLatch(1);
        var clock = new AtomicLong();
        var calls = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        Thread driver = null;
        try {
            lattice.addUpstream(blockers);
            assertTrue(blockers.offer(new AbstractFrame(1) {
                @Override
                public void execute() {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("test gate timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throwCancelSignal();
                    }
                }
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var config = ConfigLoader.load(Path.of("scenes/periodic-smoke.json"));
            try (var simulation = new SerialSimulation(config, lattice, () -> {
                /// Start-time capture and pre-offer check precede the first completion-wait check.
                if (calls.incrementAndGet() >= 3) queued.countDown();
                return clock.get();
            })) {
                var initial = simulation.state().diagnostics();
                double[][] current = simulation.state().current();
                driver = Thread.ofPlatform().unstarted(() -> {
                    try {
                        simulation.step();
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                });
                driver.start();
                assertTrue(queued.await(5, TimeUnit.SECONDS));
                assertEquals(0, simulation.state().completedSteps());
                assertSame(initial, simulation.state().diagnostics());
                assertTrue(driver.isAlive());
                if (expire) clock.set(config.config().execution().stepDeadlineMillis() * 1_000_000);
                else release.countDown();
                driver.join(5000);
                assertFalse(driver.isAlive());
                if (expire) {
                    assertInstanceOf(SimulationException.class, failure.get());
                    assertTrue(failure.get().getMessage().contains("deadline"));
                    assertEquals(0, simulation.state().completedSteps());
                    assertSame(current, simulation.state().current());
                    assertSame(initial, simulation.state().diagnostics());
                    assertThrows(IllegalStateException.class, simulation::step);
                } else {
                    assertNull(failure.get());
                    assertEquals(1, simulation.state().completedSteps());
                    assertNotSame(current, simulation.state().current());
                    assertEquals(1, simulation.state().diagnostics().step());
                }
            }
        } finally {
            release.countDown();
            if (driver != null) {
                driver.interrupt();
                driver.join(5000);
            }
            try {
                blockers.complete();
            } finally {
                lattice.close();
            }
        }
    }
}
