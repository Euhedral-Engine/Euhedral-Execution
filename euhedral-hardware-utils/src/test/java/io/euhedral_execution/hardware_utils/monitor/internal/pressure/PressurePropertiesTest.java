package io.euhedral_execution.hardware_utils.monitor.internal.pressure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.monitor.sampling.enums.SignalResolution;
import io.euhedral_execution.hardware_utils.monitor.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterDelta;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.LatencyInterval;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.ResolvedDouble;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.ResolvedLong;
import io.euhedral_execution.hardware_utils.monitor.sampling.samples.IntervalHardwareSample;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.CpuIntervalSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.CpuSlowIntervalSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.IoIntervalSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.MemoryIntervalSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.SystemSlowIntervalSignals;
import io.euhedral_execution.hardware_utils.util.UnmodifiableBitSet;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Randomized properties of {@link PressureEvaluator}. Inputs honour the sampling contract (finite,
 * non-negative gauges; {@code CURRENT}/{@code CACHED} counters have a positive elapsed time), but
 * the magnitudes are adversarial: zero, {@code Long.MAX_VALUE}, {@code Double.MAX_VALUE}, zero and
 * unlimited quota. A zero elapsed time is only representable for {@code BASELINE}/{@code UNAVAILABLE}
 * counters, which the generator produces as well.
 */
class PressurePropertiesTest {

    private static final long SEED = 0x5EED_0B0B_2026_1012L;
    private static final int SEQUENCE_LENGTH = 10_000;
    private static final int TRIALS_PER_KNOB = 300;
    private static final double EPSILON = 1e-12;
    private static final int SPAN = 4;

    // ---------------------------------------------------------------- mutable input model

    private static final class Cnt {
        long delta;
        long elapsed = 1L;
        SignalResolution res = SignalResolution.UNAVAILABLE;

        CounterDelta build() {
            return new CounterDelta(delta, elapsed, 1000L, res);
        }
    }

    private static final class Dbl {
        double value;
        SignalResolution res = SignalResolution.UNAVAILABLE;

        ResolvedDouble build() {
            return new ResolvedDouble(value, 1000L, res);
        }
    }

    private static final class Lng {
        long value;
        SignalResolution res = SignalResolution.UNAVAILABLE;

        ResolvedLong build() {
            return new ResolvedLong(value, 1000L, res);
        }
    }

    private static final class Cpu {
        final Cnt wait = new Cnt();
        final Cnt psi = new Cnt();
        final Cnt throttle = new Cnt();
        final Cnt steal = new Cnt();
        final Dbl reported = new Dbl();
        final Dbl external = new Dbl();
        final Dbl runQueue = new Dbl();
        final Dbl available = new Dbl();
        final Dbl nominal = new Dbl();
        final Lng frequency = new Lng();
        final Lng nominalFrequency = new Lng();
        ThermalSeverity thermal = ThermalSeverity.NOMINAL;
        boolean lowPower;
        SignalResolution slowRes = SignalResolution.UNAVAILABLE;
    }

    private static final class Inputs {
        final BitSet effective = new BitSet();
        final Lng quotaCpus = new Lng();
        final Lng quotaPeriod = new Lng();
        final Cnt productive = new Cnt();
        final Cnt scopeThrottle = new Cnt();
        final Cnt scopeWait = new Cnt();
        final Cnt scopePsi = new Cnt();
        final Dbl scopeReported = new Dbl();
        final Cpu[] cpus = new Cpu[SPAN];
        final Lng hardLimit = new Lng();
        final Lng highLimit = new Lng();
        final Lng usage = new Lng();
        final Lng inactive = new Lng();
        final Cnt reclaim = new Cnt();
        final Cnt memStall = new Cnt();
        final Cnt ioBytes = new Cnt();
        final Cnt ioStall = new Cnt();
        long latencyDelta;
        long latencyOps;
        long latencyElapsed = 1L;
        SignalResolution latencyRes = SignalResolution.UNAVAILABLE;
        final Dbl queue = new Dbl();
        ThermalSeverity sysThermal = ThermalSeverity.NOMINAL;
        boolean sysLowPower;
        SignalResolution sysRes = SignalResolution.UNAVAILABLE;

        Inputs() {
            for (int i = 0; i < SPAN; i++) {
                cpus[i] = new Cpu();
            }
        }

        IntervalHardwareSample build() {
            CpuIntervalSignals[] fast = new CpuIntervalSignals[SPAN];
            CpuSlowIntervalSignals[] slow = new CpuSlowIntervalSignals[SPAN];
            for (int i = 0; i < SPAN; i++) {
                Cpu c = cpus[i];
                fast[i] = new CpuIntervalSignals(
                        c.wait.build(),
                        c.psi.build(),
                        c.reported.build(),
                        c.throttle.build(),
                        c.steal.build(),
                        c.external.build(),
                        c.runQueue.build());
                slow[i] = new CpuSlowIntervalSignals(
                        c.available.build(),
                        c.nominal.build(),
                        c.frequency.build(),
                        c.nominalFrequency.build(),
                        c.thermal,
                        c.lowPower,
                        1000L,
                        c.slowRes);
            }
            MemoryIntervalSignals mem = new MemoryIntervalSignals(
                    hardLimit.build(),
                    highLimit.build(),
                    usage.build(),
                    inactive.build(),
                    reclaim.build(),
                    memStall.build());
            IoIntervalSignals io = new IoIntervalSignals(
                    ioBytes.build(),
                    ioStall.build(),
                    new LatencyInterval(latencyDelta, latencyOps, latencyElapsed, 1000L, latencyRes),
                    queue.build());
            SystemSlowIntervalSignals sys = new SystemSlowIntervalSignals(
                    new ResolvedDouble(0.0, 1000L, SignalResolution.UNAVAILABLE),
                    new ResolvedDouble(0.0, 1000L, SignalResolution.UNAVAILABLE),
                    sysThermal,
                    sysLowPower,
                    1000L,
                    sysRes);
            return new IntervalHardwareSample(
                    1000L,
                    SPAN,
                    new UnmodifiableBitSet(effective),
                    quotaCpus.build(),
                    quotaPeriod.build(),
                    productive.build(),
                    scopeThrottle.build(),
                    scopeWait.build(),
                    scopePsi.build(),
                    scopeReported.build(),
                    fast,
                    mem,
                    io,
                    slow,
                    sys);
        }
    }

    // ---------------------------------------------------------------- generators

    private static SignalResolution res(Random r) {
        int k = r.nextInt(10);
        if (k < 6) {
            return SignalResolution.CURRENT;
        }
        if (k < 7) {
            return SignalResolution.CACHED;
        }
        return k < 8 ? SignalResolution.BASELINE : SignalResolution.UNAVAILABLE;
    }

    private static long deltaValue(Random r) {
        return switch (r.nextInt(7)) {
            case 0 -> 0L;
            case 1 -> 1L;
            case 2 -> Long.MAX_VALUE;
            case 3 -> r.nextInt(1000);
            case 4 -> (long) (r.nextDouble() * 1e9);
            case 5 -> 1_000_000_000L;
            default -> r.nextLong() >>> 1;
        };
    }

    private static long elapsedValue(Random r) {
        return switch (r.nextInt(5)) {
            case 0 -> 1L;
            case 1 -> Long.MAX_VALUE;
            case 2 -> 1_000_000_000L;
            default -> 1L + ((r.nextLong() >>> 1) % 2_000_000_000L);
        };
    }

    private static double doubleValue(Random r) {
        return switch (r.nextInt(8)) {
            case 0 -> 0.0;
            case 1 -> Double.MIN_VALUE;
            case 2 -> 1.0;
            case 3 -> Double.MAX_VALUE;
            case 4 -> r.nextDouble() * 10.0;
            default -> r.nextDouble();
        };
    }

    private static long longValue(Random r) {
        return switch (r.nextInt(6)) {
            case 0 -> 0L;
            case 1 -> 1L;
            case 2 -> Long.MAX_VALUE;
            case 3 -> 1L + r.nextInt(8);
            default -> 1L + ((r.nextLong() >>> 1) % (1L << 40));
        };
    }

    private static long quotaValue(Random r) {
        return switch (r.nextInt(5)) {
            case 0 -> 0L; // zero quota
            case 1 -> Long.MAX_VALUE; // effectively unlimited
            case 2 -> 1L; // quota smaller than the effective CPU count
            default -> 1L + r.nextInt(16);
        };
    }

    private static ThermalSeverity thermal(Random r) {
        ThermalSeverity[] all = ThermalSeverity.values();
        return all[r.nextInt(all.length)];
    }

    private static void fill(Cnt c, Random r) {
        c.delta = deltaValue(r);
        c.elapsed = elapsedValue(r);
        c.res = res(r);
    }

    private static void fill(Dbl d, Random r) {
        d.value = doubleValue(r);
        d.res = res(r);
    }

    private static void fill(Lng l, Random r) {
        l.value = longValue(r);
        l.res = res(r);
    }

    private static Inputs randomInputs(Random r) {
        Inputs in = new Inputs();
        for (int i = 0; i < SPAN; i++) {
            if (r.nextInt(4) != 0) {
                in.effective.set(i);
            }
        }
        in.quotaCpus.value = quotaValue(r);
        in.quotaCpus.res = res(r);
        in.quotaPeriod.value = longValue(r);
        in.quotaPeriod.res = res(r);
        fill(in.productive, r);
        fill(in.scopeThrottle, r);
        fill(in.scopeWait, r);
        fill(in.scopePsi, r);
        fill(in.scopeReported, r);
        for (Cpu c : in.cpus) {
            fill(c.wait, r);
            fill(c.psi, r);
            fill(c.throttle, r);
            fill(c.steal, r);
            fill(c.reported, r);
            fill(c.external, r);
            fill(c.runQueue, r);
            fill(c.available, r);
            fill(c.nominal, r);
            fill(c.frequency, r);
            fill(c.nominalFrequency, r);
            c.thermal = thermal(r);
            c.lowPower = r.nextBoolean();
            c.slowRes = res(r);
        }
        fill(in.hardLimit, r);
        fill(in.highLimit, r);
        fill(in.usage, r);
        fill(in.inactive, r);
        fill(in.reclaim, r);
        fill(in.memStall, r);
        fill(in.ioBytes, r);
        fill(in.ioStall, r);
        in.latencyDelta = deltaValue(r);
        in.latencyOps = deltaValue(r);
        in.latencyElapsed = elapsedValue(r);
        in.latencyRes = res(r);
        fill(in.queue, r);
        in.sysThermal = thermal(r);
        in.sysLowPower = r.nextBoolean();
        in.sysRes = res(r);
        return in;
    }

    private static void quiet(Inputs in) {
        for (Cnt c :
                new Cnt[] {in.scopeThrottle, in.scopeWait, in.scopePsi, in.reclaim, in.memStall, in.ioBytes, in.ioStall
                }) {
            c.delta = 0L;
        }
        for (Dbl d : new Dbl[] {in.scopeReported, in.queue}) {
            d.value = 0.0;
        }
        for (Cpu c : in.cpus) {
            for (Cnt n : new Cnt[] {c.wait, c.psi, c.throttle, c.steal}) {
                n.delta = 0L;
            }
            for (Dbl d : new Dbl[] {c.reported, c.external, c.runQueue}) {
                d.value = 0.0;
            }
            c.available.value = c.nominal.value;
            c.frequency.value = c.nominalFrequency.value;
            c.thermal = ThermalSeverity.NOMINAL;
            c.lowPower = false;
        }
        in.usage.value = 0L;
        in.inactive.value = 0L;
        in.latencyDelta = 0L;
        in.sysThermal = ThermalSeverity.NOMINAL;
        in.sysLowPower = false;
    }

    // ---------------------------------------------------------------- finite and bounded

    @Test
    void everyCellIsFiniteAndWithinUnitInterval() {
        Random random = new Random(SEED);
        PressureState state = new PressureState(SPAN);
        long evaluationNs = 1_000_000L;

        for (int step = 0; step < SEQUENCE_LENGTH; step++) {
            evaluationNs += switch (random.nextInt(8)) {
                case 0 -> 0L; // duplicate timestamp, exercises the dt <= 0 branch
                case 1 -> 1L;
                case 2 -> 1_000_000_000_000L;
                default -> 1L + (random.nextLong() >>> 1) % 2_000_000_000L;
            };
            Inputs in = randomInputs(random);
            PressureEvaluation eval;
            try {
                eval = PressureEvaluator.evaluate(in.build(), state, evaluationNs);
            } catch (RuntimeException e) {
                throw new AssertionError("seed=" + SEED + " step=" + step + " evaluate threw", e);
            }
            state = eval.state();
            assertCells(eval, "seed=" + SEED + " step=" + step);
        }
    }

    private static void assertCells(PressureEvaluation eval, String context) {
        double[] cells = eval.state().previous;
        for (int i = 0; i < cells.length; i++) {
            assertUnit(cells[i], context + " stateCell=" + i);
        }
        HardwareUtilization util = eval.candidate();
        assertUnit(util.cpuThrottleRatio(), context + " cpuThrottleRatio");
        assertUnit(util.totalMemoryUtilization(), context + " totalMemoryUtilization");
        assertUnit(util.diskIOPressure(), context + " diskIOPressure");
        assertUnit(util.quotaCpuUsage(), context + " quotaCpuUsage");
        for (int i = 0; i < SPAN; i++) {
            assertUnit(util.perQuotaCpuPressure().get(i), context + " perQuotaCpuPressure[" + i + "]");
            assertUnit(util.perQuotaCpuThrottleRatio().get(i), context + " perQuotaCpuThrottleRatio[" + i + "]");
            assertUnit(util.snapshot().pressurePerCpu().get(i), context + " snapshot.pressurePerCpu[" + i + "]");
        }
    }

    private static void assertUnit(double v, String what) {
        assertTrue(Double.isFinite(v), what + " is not finite: " + v);
        assertTrue(v >= 0.0 && v <= 1.0, what + " outside [0,1]: " + v);
    }

    // ---------------------------------------------------------------- monotonicity

    private record Knob(String name, Consumer<Inputs> prepare, BiConsumer<Inputs, Random> raise) {}

    private static void live(Cnt c) {
        c.res = SignalResolution.CURRENT;
    }

    private static void live(Dbl d) {
        d.res = SignalResolution.CURRENT;
    }

    private static void live(Lng l) {
        l.res = SignalResolution.CURRENT;
    }

    private static Knob counter(String name, java.util.function.Function<Inputs, Cnt> sel, Consumer<Inputs> extra) {
        return new Knob(
                name,
                in -> {
                    in.effective.set(0);
                    live(sel.apply(in));
                    extra.accept(in);
                },
                (in, r) -> {
                    Cnt c = sel.apply(in);
                    c.delta = Math.max(c.delta, deltaValue(r));
                });
    }

    private static Knob gaugeUp(String name, java.util.function.Function<Inputs, Dbl> sel) {
        return new Knob(
                name,
                in -> {
                    in.effective.set(0);
                    live(sel.apply(in));
                },
                (in, r) -> {
                    Dbl d = sel.apply(in);
                    d.value = Math.max(d.value, doubleValue(r));
                });
    }

    private static void liveMemory(Inputs in) {
        live(in.hardLimit);
        in.hardLimit.value = Math.max(1L, in.hardLimit.value);
        live(in.usage);
    }

    private static List<Knob> knobs() {
        List<Knob> k = new ArrayList<>();
        k.add(counter("scope.wait", in -> in.scopeWait, in -> {}));
        k.add(counter("scope.psi", in -> in.scopePsi, in -> {}));
        k.add(counter("scope.throttle", in -> in.scopeThrottle, in -> {}));
        k.add(gaugeUp("scope.reported", in -> in.scopeReported));
        k.add(counter("cpu0.wait", in -> in.cpus[0].wait, in -> {}));
        k.add(counter("cpu0.psi", in -> in.cpus[0].psi, in -> {}));
        k.add(counter("cpu0.throttle", in -> in.cpus[0].throttle, in -> {}));
        k.add(counter("cpu0.steal", in -> in.cpus[0].steal, in -> {}));
        k.add(gaugeUp("cpu0.reported", in -> in.cpus[0].reported));
        k.add(gaugeUp("cpu0.external", in -> in.cpus[0].external));
        k.add(gaugeUp("cpu0.runQueue", in -> in.cpus[0].runQueue));
        k.add(new Knob(
                "cpu0.capacityLoss(lower available)",
                in -> {
                    in.effective.set(0);
                    live(in.cpus[0].available);
                    live(in.cpus[0].nominal);
                    in.cpus[0].nominal.value = Math.max(1.0, in.cpus[0].nominal.value);
                },
                (in, r) -> in.cpus[0].available.value = Math.min(in.cpus[0].available.value, doubleValue(r))));
        k.add(new Knob(
                "cpu0.frequencyLoss(lower frequency)",
                in -> {
                    in.effective.set(0);
                    live(in.cpus[0].frequency);
                    live(in.cpus[0].nominalFrequency);
                    in.cpus[0].nominalFrequency.value = Math.max(1L, in.cpus[0].nominalFrequency.value);
                },
                (in, r) -> in.cpus[0].frequency.value = Math.min(in.cpus[0].frequency.value, longValue(r))));
        k.add(new Knob(
                "cpu0.thermal",
                in -> {
                    in.effective.set(0);
                    in.cpus[0].slowRes = SignalResolution.CURRENT;
                },
                (in, r) -> {
                    ThermalSeverity c = thermal(r);
                    if (c.ordinal() > in.cpus[0].thermal.ordinal()) {
                        in.cpus[0].thermal = c;
                    }
                }));
        k.add(new Knob(
                "cpu0.lowPower",
                in -> {
                    in.effective.set(0);
                    in.cpus[0].slowRes = SignalResolution.CURRENT;
                    in.cpus[0].lowPower = false;
                },
                (in, r) -> in.cpus[0].lowPower = true));
        k.add(new Knob(
                "system.thermal",
                in -> {
                    in.effective.set(0);
                    in.sysRes = SignalResolution.CURRENT;
                },
                (in, r) -> {
                    ThermalSeverity c = thermal(r);
                    if (c.ordinal() > in.sysThermal.ordinal()) {
                        in.sysThermal = c;
                    }
                }));
        k.add(new Knob(
                "system.lowPower",
                in -> {
                    in.effective.set(0);
                    in.sysRes = SignalResolution.CURRENT;
                    in.sysLowPower = false;
                },
                (in, r) -> in.sysLowPower = true));
        k.add(new Knob(
                "memory.usage",
                in -> {
                    in.effective.set(0);
                    liveMemory(in);
                },
                (in, r) -> in.usage.value = Math.max(in.usage.value, longValue(r))));
        k.add(new Knob(
                "memory.hardLimit(lower limit)",
                in -> {
                    in.effective.set(0);
                    liveMemory(in);
                },
                (in, r) -> in.hardLimit.value = Math.min(in.hardLimit.value, longValue(r))));
        k.add(counter("memory.reclaim", in -> in.reclaim, in -> {
            in.effective.set(0);
            liveMemory(in);
        }));
        k.add(counter("memory.stall", in -> in.memStall, in -> {}));
        k.add(counter("io.stall", in -> in.ioStall, in -> {}));
        k.add(new Knob(
                "io.latency",
                in -> {
                    in.effective.set(0);
                    in.latencyRes = SignalResolution.CURRENT;
                    in.latencyOps = Math.max(1L, in.latencyOps);
                },
                (in, r) -> in.latencyDelta = Math.max(in.latencyDelta, deltaValue(r))));
        k.add(gaugeUp("io.queueDepth", in -> in.queue));
        return k;
    }

    @Test
    void raisingOneInputNeverLowersPressure() {
        long seed = SEED;
        for (Knob knob : knobs()) {
            Random random = new Random(seed ^ knob.name().hashCode());
            int strictlyRaised = 0;
            for (int trial = 0; trial < TRIALS_PER_KNOB; trial++) {
                int historyLength = random.nextInt(4);
                long[] historySeeds = new long[historyLength];
                long[] times = new long[historyLength + 1];
                long t = 1_000_000L;
                for (int h = 0; h < historyLength; h++) {
                    historySeeds[h] = random.nextLong();
                    t += 1L + (random.nextLong() >>> 1) % 2_000_000_000L;
                    times[h] = t;
                }
                t += 1L + (random.nextLong() >>> 1) % 2_000_000_000L;
                times[historyLength] = t;
                long finalSeed = random.nextLong();
                long raiseSeed = random.nextLong();

                // Half of the trials start from a quiet baseline so that the raised input is not
                // always masked by a larger, unrelated contribution in the max() composition.
                boolean quiet = (trial & 1) == 0;
                Inputs base = randomInputs(new Random(finalSeed));
                if (quiet) {
                    quiet(base);
                }
                knob.prepare().accept(base);
                Inputs raised = randomInputs(new Random(finalSeed));
                if (quiet) {
                    quiet(raised);
                }
                knob.prepare().accept(raised);
                knob.raise().accept(raised, new Random(raiseSeed));

                String context = "seed=" + SEED + " knob=" + knob.name() + " trial=" + trial;
                PressureEvaluation low = run(historySeeds, times, base);
                PressureEvaluation high = run(historySeeds, times, raised);
                assertNotLower(low, high, context);
                if (sum(high) > sum(low)) {
                    strictlyRaised++;
                }
            }
            assertTrue(
                    strictlyRaised > 0,
                    "seed=" + SEED + " knob=" + knob.name() + " never produced a strictly higher output;"
                            + " the property would be vacuous");
        }
    }

    private static PressureEvaluation run(long[] historySeeds, long[] times, Inputs last) {
        PressureState state = new PressureState(SPAN);
        for (int h = 0; h < historySeeds.length; h++) {
            state = PressureEvaluator.evaluate(
                            randomInputs(new Random(historySeeds[h])).build(), state, times[h])
                    .state();
        }
        return PressureEvaluator.evaluate(last.build(), state, times[historySeeds.length]);
    }

    private static double sum(PressureEvaluation e) {
        HardwareUtilization u = e.candidate();
        double s = u.cpuThrottleRatio() + u.totalMemoryUtilization() + u.diskIOPressure();
        for (int i = 0; i < SPAN; i++) {
            s += u.perQuotaCpuPressure().get(i)
                    + u.perQuotaCpuThrottleRatio().get(i)
                    + u.snapshot().pressurePerCpu().get(i);
        }
        return s;
    }

    private static void assertNotLower(PressureEvaluation low, PressureEvaluation high, String context) {
        HardwareUtilization l = low.candidate();
        HardwareUtilization h = high.candidate();
        assertGe(h.cpuThrottleRatio(), l.cpuThrottleRatio(), context + " cpuThrottleRatio");
        assertGe(h.totalMemoryUtilization(), l.totalMemoryUtilization(), context + " totalMemoryUtilization");
        assertGe(h.diskIOPressure(), l.diskIOPressure(), context + " diskIOPressure");
        for (int i = 0; i < SPAN; i++) {
            assertGe(
                    h.perQuotaCpuPressure().get(i),
                    l.perQuotaCpuPressure().get(i),
                    context + " perQuotaCpuPressure[" + i + "]");
            assertGe(
                    h.perQuotaCpuThrottleRatio().get(i),
                    l.perQuotaCpuThrottleRatio().get(i),
                    context + " perQuotaCpuThrottleRatio[" + i + "]");
            assertGe(
                    h.snapshot().pressurePerCpu().get(i),
                    l.snapshot().pressurePerCpu().get(i),
                    context + " snapshot.pressurePerCpu[" + i + "]");
        }
    }

    private static void assertGe(double raised, double baseline, String what) {
        assertTrue(raised >= baseline - EPSILON, what + " fell from " + baseline + " to " + raised);
    }
}
