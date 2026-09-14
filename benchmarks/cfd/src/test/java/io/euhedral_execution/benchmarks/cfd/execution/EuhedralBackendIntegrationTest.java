package io.euhedral_execution.benchmarks.cfd.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.geometry.GeometryMask;
import io.euhedral_execution.benchmarks.cfd.solver.Simulation;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import io.euhedral_execution.benchmarks.cfd.support.CfdTestRuntime;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import io.euhedral_execution.data_structures.queues.common.ConcurrentPartitionedQueue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
@Isolated
class EuhedralBackendIntegrationTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7})
    void rejectedOffersRetryTheSameFrameAndSourceAndEveryGenerationStartsAtZero(int sources) throws Exception {
        var config = ConfigLoader.load(
                Path.of("scenes/periodic-smoke.json"),
                List.of("execution.steps=3", "execution.brick.nx=3", "execution.brick.ny=5", "execution.brick.nz=7"));
        var lattice = CfdTestRuntime.upToTwoWorkers();
        try (AutoCloseable runtime = lattice::close) {
            var attempts = new ArrayList<Offer>();
            var sinks = new QueueIngestSink[sources];
            for (int source = 0; source < sources; source++) {
                int ordinal = source;
                var reject = new AtomicBoolean(true);
                sinks[source] = observedSink((frame) -> {
                    boolean accepted = !reject.getAndSet(false);
                    attempts.add(new Offer(ordinal, frame, frame.step(), accepted));
                    return accepted;
                });
            }
            var geometry = GeometryMask.resolve(config, lattice);
            var expected =
                    RangePlan.create(geometry, config.config().execution().brick());
            try (var backend = new EuhedralBackend(lattice, true, sinks, false, 2000);
                    var simulation = new Simulation(config, geometry, backend)) {
                simulation.run();
                for (int step = 1; step <= 3; step++) {
                    int successful = 0;
                    int[] counts = new int[sources];
                    for (int i = 0; i < attempts.size(); i++) {
                        var offer = attempts.get(i);
                        if (offer.step != step) {
                            continue;
                        }
                        assertEquals(successful % sources, offer.source);
                        assertEquals(expected[successful].rangeId(), offer.frame.rangeId());
                        assertNotEquals(offer.frame.getIdHash(), offer.frame.getRoutingHash());
                        if (!offer.accepted) {
                            var retry = attempts.get(i + 1);
                            assertEquals(offer.source, retry.source);
                            assertSame(offer.frame, retry.frame);
                            assertEquals(offer.step, retry.step);
                        } else {
                            successful++;
                            counts[offer.source]++;
                        }
                    }
                    assertEquals(expected.length, successful);
                    for (int a : counts) {
                        for (int b : counts) {
                            assertTrue(Math.abs(a - b) <= 1);
                        }
                    }
                }
                var first = attempts.stream()
                        .filter(offer -> offer.accepted && offer.step == 1)
                        .toList();
                var last = attempts.stream()
                        .filter(offer -> offer.accepted && offer.step == 3)
                        .toList();
                for (int i = 0; i < first.size(); i++) {
                    assertSame(first.get(i).frame, last.get(i).frame);
                }
            }
        }
    }

    @Test
    void permanentOfferRejectionStopsAtDeadlineWithoutSubmittingToAnotherSource() throws Exception {
        var config =
                ConfigLoader.load(Path.of("scenes/periodic-smoke.json"), List.of("execution.stepDeadlineMillis=10"));
        var lattice = CfdTestRuntime.singleWorker();
        try (AutoCloseable runtime = lattice::close) {
            int[] attempts = new int[2];
            var clock = new java.util.concurrent.atomic.AtomicLong();
            var sinks = new QueueIngestSink[] {
                observedSink(frame -> {
                    attempts[0]++;
                    clock.set(10_000_000);
                    return false;
                }),
                observedSink(frame -> {
                    attempts[1]++;
                    return true;
                })
            };
            try (var backend = new EuhedralBackend(lattice, true, sinks, false, 1000);
                    var simulation =
                            new Simulation(config, GeometryMask.resolve(config, lattice), backend, clock::get) {}) {
                var failure = assertThrows(SimulationException.class, simulation::step);
                assertTrue(failure.getMessage().contains("outstanding range ordinals"));
                assertTrue(attempts[0] > 0);
                assertEquals(0, attempts[1]);
                assertEquals(0, simulation.state().completedSteps());
            }
        }
    }

    private record Offer(int source, CfdRangeFrame frame, long step, boolean accepted) {}

    @SuppressWarnings("unchecked")
    private static QueueIngestSink observedSink(java.util.function.Predicate<CfdRangeFrame> accept) {
        var queue = new PartitionedMpscQueue<AbstractFrame>(1, 64);
        var proxy = (ConcurrentPartitionedQueue<AbstractFrame>) Proxy.newProxyInstance(
                ConcurrentPartitionedQueue.class.getClassLoader(),
                new Class<?>[] {ConcurrentPartitionedQueue.class},
                (ignored, method, args) -> {
                    if (method.getName().equals("offer") && args.length == 1 && !accept.test((CfdRangeFrame) args[0])) {
                        return false;
                    }
                    try {
                        return method.invoke(queue, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        return new QueueIngestSink(proxy);
    }
}
