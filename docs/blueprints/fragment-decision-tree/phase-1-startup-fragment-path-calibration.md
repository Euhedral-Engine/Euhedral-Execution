# Startup Fragment Path Calibration Blueprint

## Status

- Date: 2026-08-10
- Source plan: [`startup-fragment-path-calibration.md`](../../plans/fragment-decision-tree/phase-1-startup-fragment-path-calibration.md)
- Blueprint intensity: high
- Implementation intensity: medium
- Scope: one Core diagnostic seam, focused tests, and one JMH benchmark
- Recorded result: supported as a feasibility signal; production implementation remains unapproved

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

Add one package-private, setup-only override to `FragmentDecisionTree`:

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
polling without hiding the extra queue hop. The diagnostic override still clamps to the existing
eligible cap. Discard the initial batch of two. If either mode does not remain at an effective batch
of 32 after that boundary, the run is invalid; do not select a different batch during implementation.

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

## Recorded Result

The experiment supports the simple separator on this host. This is not approval to implement a
startup threshold. The no-op body predicts direct and the fixed CPU body predicts staged; those
predictions match all four rows under the rule above. The CPU/plentiful intervals overlap, but the
mean difference is 5.0 percent, so the predeclared less-than-two-percent tie rule selects staged.

The result is weaker than the four labels alone imply. Direct CPU/plentiful throughput was bimodal:
one fork ran near 4.24 million frames/s and two ran near 8.5 million frames/s. Direct no-op/plentiful
showed a similar 81.7 versus 144 million frames/s split. The scarce direct fixtures and both staged
fixtures were materially more stable. A production pass must return to design and explain this
fork-level effect; it must not guess that the startup scalar is sufficient under every runtime
condition.

### Environment and protocol

- Date: 2026-08-10
- CPU: Intel Core i9-14900K, one socket, 24 physical cores, 32 logical CPUs
- Effective process CPUs: 0-31
- Cache: 36 MiB shared L3
- JVM: OpenJDK 64-Bit Server VM 21.0.2+13-58
- JMH: 1.37, three forks, three 3-second warmups, five 5-second measurements
- Workers: one for intrinsic overhead; two same-kind cores on the same socket for decision rows
- Batch cap and forced target: 32 frames
- Frames per throughput invocation and calibration window: 1,048,576

The scheduler rows were run through `core-fragment-path-calibration`. After changing throughput
display from `ops/ns` to `ops/s`, the average-time controls inherited seconds and lost display
precision. Those already-completed scheduler rows were retained, the redundant control tail was
stopped, and the two controls were rerun with their final method-level `ns/op` declaration. No
fixture behavior changed between those measurements.

### Calibration windows

Values below are the full-precision elapsed nanoseconds per completed frame. Each bracket is one
fork's nine disjoint windows; the median is the fifth value after the specified in-place sort.

Direct startup:

```text
[11.14990974462449, 11.155663076148482, 11.178313142717647, 11.187066117909128, 11.224094064693316, 12.285429292086455, 12.802006047320138, 14.446519469571701, 33.45059638766008]
[11.278574950149576, 11.280182060742222, 11.300066661135414, 11.313633459524922, 11.877815856523862, 12.443587083387369, 14.724118200180433, 16.6014369898543, 47.13623428458155]
[11.252024751894584, 11.259640399462906, 11.261734432126149, 11.27517341079047, 11.343876092303262, 12.339995594306165, 12.62038144280742, 14.145484002624272, 48.282820438546004]
```

Direct warmed:

```text
[11.156806220563372, 11.162347607494889, 11.16697883605957, 11.167779974196058, 11.168458213971018, 11.175873713402359, 11.20182178300909, 11.29582930027417, 11.541423193402956]
[11.211479521289277, 11.219749714631195, 11.224794203362935, 11.234393133312418, 11.23518658033015, 11.235683361306073, 11.23643054891964, 11.261037321289614, 11.26506373365654]
[11.284420747669078, 11.31294004556495, 11.353510579561027, 11.429777766758088, 11.442864090631675, 11.459691854072677, 11.47820548028805, 11.510387762769206, 11.515544468823812]
```

Staged startup:

```text
[27.634946823120117, 28.010239617344602, 28.060484290085814, 28.219299575572585, 28.288391413139667, 28.589056536839635, 28.70762640749443, 29.504294026887493, 76.30544800884955]
[27.710374134090145, 27.726927757263184, 27.811418566327934, 27.90011329305136, 28.047788297584216, 28.627289832281615, 28.731368633464555, 29.570331334492966, 74.34137672705646]
[27.45411810026546, 27.61698651927126, 27.73349837973515, 27.792417851635623, 27.797357529752823, 28.175055309734514, 28.47719644525812, 29.20937089932558, 80.0320135034617]
```

Staged warmed:

```text
[27.760071483813626, 27.766698708962497, 27.77996063232422, 27.794864532642144, 27.80380991436037, 27.88399314880371, 27.909292651479316, 28.06101846192282, 28.17345995878538]
[27.689733666980732, 27.698197489397696, 27.698576721166088, 27.72573080788912, 27.74221134185791, 27.794344975563238, 27.868410110473633, 27.948828865943987, 29.242854118347168]
[27.562773403115997, 27.593701362609863, 27.633008003234863, 27.66315690092867, 27.695661295736823, 27.820448314172776, 27.841451831620947, 27.90312490463548, 28.2718824797124]
```

| Path | Startup fork medians (ns/frame) | Representative startup median | Warmed fork medians (ns/frame) | Representative warmed median | Largest startup delta |
|---|---|---:|---|---:|---:|
| direct | 11.224, 11.878, 11.344 | 11.344 | 11.168, 11.235, 11.443 | 11.235 | 5.72% |
| staged | 28.288, 28.048, 27.797 | 28.048 | 27.804, 27.742, 27.696 | 27.742 | 1.74% |

The staged sanity delta is 16.704 ns/frame at startup and 16.507 ns/frame warmed. Staged is never
below direct, so the fixture's path ordering is credible. Every startup/warmed pair is within the
required ten percent.

### Raw JMH measurement samples

Throughput lists are millions of frames per second. Each semicolon separates one fork; each fork
contains its five measurement iterations.

```text
CPU direct plentiful: 4.200698,4.235525,4.233819,4.238509,4.237513; 8.528690,8.533255,8.536508,8.529801,8.522475; 8.475875,8.470788,8.478549,8.479405,8.477879
CPU direct scarce: 4.265095,4.257310,4.266527,4.261915,4.265090; 4.234607,4.231803,4.234176,4.233592,4.232369; 4.263476,4.263465,4.266540,4.262224,4.266674
CPU staged plentiful: 7.451921,7.448411,7.445798,7.461814,7.466619; 7.420656,7.424053,7.437828,7.430321,7.430435; 7.419372,7.424030,7.422432,7.424263,7.420436
CPU staged scarce: 7.470613,7.468456,7.468191,7.470795,7.469638; 7.477860,7.473382,7.473026,7.480105,7.467930; 7.458431,7.448947,7.453859,7.458558,7.458215
no-op direct plentiful: 144.001130,144.223850,144.097838,143.834714,143.860721; 81.770362,81.694126,81.846244,81.584843,81.659771; 144.437567,143.473148,143.999248,144.383273,144.371895
no-op direct scarce: 81.485847,81.432020,81.349792,81.020350,81.386572; 81.458730,80.593818,81.104624,81.361112,81.619338; 81.209262,81.134742,78.367331,77.828779,78.176790
no-op staged plentiful: 38.685679,38.783745,38.796024,38.374939,37.746314; 36.988554,37.053092,37.010159,36.740139,36.293510; 39.372758,39.404693,39.445692,39.471935,38.965436
no-op staged scarce: 44.526759,44.120449,44.126470,44.137644,44.165943; 40.524331,41.172392,40.891898,40.675050,40.877767; 47.586192,47.487633,47.550047,47.641845,47.486596
intrinsic direct: 89.472826,89.492769,89.503925,89.226801,88.704563; 88.513284,88.762960,88.662619,88.717403,88.672483; 88.565484,88.701598,88.637268,88.433375,87.884510
intrinsic staged: 35.838116,35.889101,35.855943,35.764724,35.808190; 35.906558,35.987490,35.958897,35.967190,35.806420; 35.960224,35.950355,36.016669,36.054667,36.018667
```

Work-only lists are nanoseconds per operation:

```text
CPU: 225.906,225.307,227.051,225.791,225.361; 224.901,224.875,224.161,224.994,225.465; 224.768,223.186,224.892,224.928,225.011
no-op: 0.267,0.266,0.268,0.268,0.266; 0.267,0.267,0.266,0.268,0.266; 0.331,0.326,0.316,0.309,0.325
```

### Means, confidence intervals, and prediction

JMH errors and intervals are 99.9 percent. Throughput is reported in millions of frames per second.

| Work | Sources | Mode | Mean | Error | Confidence interval | Predicted | Observed |
|---|---|---|---:|---:|---|---|---|
| no-op | plentiful | direct | 123.283 | 32.530 | 90.753-155.812 | direct | direct |
| no-op | plentiful | staged | 38.209 | 1.203 | 37.006-39.411 | direct | direct |
| no-op | scarce | direct | 80.635 | 1.418 | 79.218-82.053 | direct | direct |
| no-op | scarce | staged | 44.198 | 3.042 | 41.156-47.240 | direct | direct |
| CPU | plentiful | direct | 7.079 | 2.230 | 4.849-9.308 | staged | staged, overlapping CI |
| CPU | plentiful | staged | 7.435 | 0.017 | 7.418-7.452 | staged | staged, overlapping CI |
| CPU | scarce | direct | 4.254 | 0.016 | 4.238-4.270 | staged | staged |
| CPU | scarce | staged | 7.467 | 0.010 | 7.457-7.476 | staged | staged |

The intrinsic throughput controls were direct 88.797 +/- 0.479 million frames/s, CI
88.318-89.276 million, and staged 35.919 +/- 0.095 million frames/s, CI 35.824-36.013 million.
The work-only controls were no-op 0.285 +/- 0.029 ns/op, CI 0.256-0.314, and CPU
225.106 +/- 0.904 ns/op, CI 224.202-226.011. CPU work is 19.8 times the representative direct
startup median and therefore passes the required two-times validity bound.

### Verdict and design boundary

Result: **SUPPORTED** for the feasibility experiment on this hardware and JVM.

This result does not reduce the production work to a mechanical threshold edit. The implementation
intensity remains medium only if the existing graph can provide a deterministic pre-ingest
calibration barrier and a batch-level work estimate with no new coordination. The observed
fork-level bimodality and the unresolved lifecycle, cost-subtraction, and interruption questions
still require returning to design. If resolving them requires broader shared state, per-frame
timing, source classification, or continuous adaptation, stop: that would exceed the payoff and the
scope of this experiment.

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
