# Startup Fragment Path Calibration Blueprint

## Status

- Date: 2026-08-10
- Source plan: [`startup-fragment-path-calibration.md`](../plans/startup-fragment-path-calibration.md)
- Blueprint intensity: high
- Implementation intensity: medium
- Scope: one Core diagnostic seam, focused tests, and one JMH benchmark

This blueprint does not approve a production policy change. It specifies the smallest experiment
that can show whether branch-overhead calibration contains enough information to justify one.

## Decision

Measure the real direct and staged graph paths under forced, trial-long modes. Use no-op
`BenchmarkFrame` instances for scheduler overhead and a benchmark executor for one fixed CPU-work
fixture. Compare both modes with plentiful and scarce sources.

Do not add startup calibration, metrics, shared controller state, lifecycle hooks, or new public
configuration in this pass. Normal runtime construction, benchmark-mode behavior, reset behavior,
and policy decisions remain unchanged.

## What the Experiment Must Measure

The no-op comparison includes the existing productive branch operations:

```text
DIRECT: local drain -> remote drain -> locked upstream pull -> downstream execution
STAGED: upstream request -> local drain -> remote drain -> locked upstream pull fallback
        -> downstream execution
```

It excludes fully idle waits, source setup, topology setup, teardown, and harness polling. The staged
measurement includes `request()` time even though the current service estimate does not. Otherwise
it would omit the operation that creates the staged path's additional work.

Use identical unordered routing hashes, worker CPU sets, source arrays, fixed batch target, and
completion accounting for the two modes. Keep the target fixed for a comparison so batch
adaptation cannot be mistaken for branch overhead. Ordered frames are covered by regression tests,
not by the performance comparison, because they intentionally stop direct pull.

`BenchmarkFrame` is the no-op body. Do not add another Core frame type. A benchmark-local executor
increments the existing padded completion counter after consuming the frame. Its CPU-work variant
performs a fixed deterministic arithmetic workload before incrementing the same counter. A separate
work-only JMH method measures that body without the scheduler.

## Diagnostic Mode Pin

Add one package-private, setup-only override to `FragmentControlPolicy`:

- The override contains a fixed `Mode` and fixed batch size.
- Benchmark setup publishes it before constructing any fragments.
- Each policy constructor captures the override once. Normal instances capture `null`.
- A captured override prevents mode transitions and returns
  `min(fixedBatchSize, eligibleCap)` at completed-batch boundaries, with the existing floor of two.
- Teardown closes every fragment and distributor before clearing the override in a `finally` block.
- Installing a second override while one is active fails immediately.

Use a normal JDK `AtomicReference` for this low-frequency setup state. A release publication and
constructor acquire read make the immutable override visible to worker construction. The captured
reference is final and owner-local; the hot loop performs no shared read. Override handling occurs
only at completed-batch boundaries, never per frame, and the benchmark must report any resolved
no-op regression caused by the seam.

Keep the installer, clearer, override record, and fixed-mode behavior package-private. Place the JMH
class in the same Java package within the benchmark module so no public API, module export, system
property, or configuration field is added. Do not route this seam through the existing benchmark
mode.

Add `///` declaration comments to every new class and method and every changed signature, including
benchmark fixtures.

## Benchmark Fixture

Register one `core-fragment-path-calibration` JMH selection in `BenchRunner` and the benchmark
README. Use the existing launcher flags and reserve the harness core using the established
high-contention benchmark's selection logic.

Build the benchmark graph directly from real `ControlPlaneFragment`, `LatticeVertex`, `LatticeEdge`,
and `AbstractExecutor` instances; do not start the lattice singleton or resource monitor. Start and
register the pinned fragments before ingesting sources, attach one benchmark-local counting
executor to each fragment output, and close every object at trial teardown. This retains the actual
fragment, upstream-handle, routing-cache, local-cache, and execution paths while keeping hardware
pressure updates from changing the comparison batch.

Use one pinned worker to collect the direct and staged no-op overhead medians in nanoseconds per
frame. Use at least two same-kind workers on one socket for the source-availability comparison. If
the host cannot provide that topology, report the multi-worker fixture as unsupported rather than
mixing core types or sockets. Results apply only to the tested core kind and architecture.

Run these four workload shapes in both forced modes:

| Work | Sources | Purpose |
|---|---:|---|
| no-op | at least one independent source per worker | Direct-overhead calibration under plentiful work |
| no-op | one shared source | Detect source-handle effects even without frame work |
| fixed CPU work | at least one independent source per worker | Test the threshold when direct pulls need not serialize workers |
| fixed CPU work | one shared source | Test whether staging unlocks parallel execution |

This is a two-axis falsification fixture, not a search over source counts, batches, or work levels.
Use one fixed batch size for every row. Start with 32 because it is large enough to amortize harness
polling without hiding the extra queue hop. Construct each fragment with a configured maximum of 32;
the diagnostic override still clamps to the existing eligible cap. Discard the initial batch of two.
If either mode does not remain at an effective batch of 32 after that boundary, the run is invalid;
do not select a different batch during implementation.

Use deterministic `BenchmarkFrame.generate(..., routingSeed)` calls and preallocate all frames and
sources at trial setup. Repeating sources may reuse those immutable frames. Do not allocate, log,
format, or read timers in the per-frame executor. Completion waits use an absolute padded-counter
target and the existing bounded spin/yield pattern.

Use the repository's standard JMH pattern:

- three forks;
- three warmup iterations of at least three seconds;
- five measurement iterations of at least five seconds; and
- throughput output plus a work-only nanoseconds-per-operation result.

Retain raw iteration results. One calibration sample is the elapsed time for a fixed completion
target divided by its completed-frame count, with one timer pair around the whole target. Collect
nine disjoint samples immediately after graph startup and nine after JMH warmup, then compute each
median with an in-place sort of the fixed array. Report the normal JMH mean and confidence interval
as the performance result. Do not add percentile libraries or an online statistics framework.

The fixed CPU-work fixture is valid only if its work-only median is at least twice the direct no-op
median. The no-op body represents the below-threshold point. If the CPU fixture misses that bound on
the test host, record the run as inconclusive and return to this blueprint; do not tune a range of
work values in the same pass.

The startup median is representative only when it is within ten percent of the post-warmup median.
Failure of that check invalidates startup calibration on the tested JVM even if steady-state mode
comparisons are otherwise clear. Do not add a longer warmup loop in the same implementation pass.

## Prediction and Result Rule

For each valid workload row:

```text
predicted mode = DIRECT when work-only median < direct no-op median
                 STAGED otherwise

observed mode  = mode with higher JMH throughput
```

Treat modes as tied when their confidence intervals overlap and the absolute difference is below
two percent. A tie favors direct because it is the smaller path.

The idea is disproved if either condition occurs:

- plentiful and scarce sources select different observed modes for the same work-only cost; or
- the threshold predicts a different mode from a resolved observed winner.

The first condition is decisive even if another threshold could fit the four results. Fitting a new
constant would hide the missing source-availability variable and is outside scope.

Report the staged no-op median and `staged - direct` as a sanity value. A staged value below direct
in the single-worker or plentiful no-op fixture requires investigation of the fixture before any
policy conclusion.

## Focused Tests

Core tests are deterministic and use no clocks:

- normal policy construction ignores an absent diagnostic override;
- an installed override is captured by new policies but does not mutate existing policies;
- fixed direct and fixed staged modes do not transition after service samples;
- fixed batch size remains within the eligible cap and floor;
- double installation fails, and clear restores normal construction; and
- policy reset retains a captured diagnostic mode while normal reset retains current semantics.

Benchmark tests cover deterministic routing seeds, source-count construction, absolute completion
targets, and teardown cleanup. Existing fragment tests remain the authority for local-cache-first,
direct pull, staged request, ordered fallback, pressure caps, owner-thread reset, drain, and close.

## Acceptance and Design Boundary

The feasibility implementation is complete when focused tests and `mise exec -- gradle
:euhedral-core:test :benchmarks:test :benchmarks:assemble` pass, `git diff --check` is clean, and the
JMH result records hardware, effective CPUs, JVM, batch size, raw samples, medians, means, confidence
intervals, and the prediction outcome.

Do not proceed to production startup calibration unless every workload row is valid and the simple
rule predicts every resolved winner across all forks.

Even after a pass, return to design before production implementation. The following decisions still
require evidence and must not be guessed:

- where to place a pre-ingest barrier after the real graph is connected;
- how many synthetic iterations make cold startup representative of compiled steady state;
- how to subtract mode-specific path cost from the current mixed service sample; and
- what happens when calibration is interrupted by topology change, shutdown, or a missing sample.

If answering those questions requires a controller thread, ongoing cross-core coordination,
per-frame timing, source classification, or a public configuration surface, reject the production
feature as too large for its expected payoff.
