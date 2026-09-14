# CFD execution benchmarks

`bench` compares the Java execution backends with JMH. OpenLB supplies numerical evidence;
its runtime is never a scheduler baseline. `normal.json` is the stock application workload:
a **256 x 256 x 256 periodic-shear domain with 1,000 timed steps per invocation**. That is
16,777,216 fluid cells and 16,777,216,000 cell updates per invocation. Its 32-cubed bricks
reuse 512 range frames. `smoke.json` retains tiny periodic, obstacle, duct, and STL fixtures
for checking the harness. Neither preset changes production scheduling defaults.

```bash
mise exec -- gradle :benchmarks:cfd:build
OPENLB_HOME=/path/to/pinned-openlb mise exec -- benchmarks/cfd/build/bin/euhedral-cfd bench --config benchmarks/cfd/suites/normal.json
```

The application distribution bundles `suites`, `validation`, and `scenes`. From an extracted
application distribution, run `bin/euhedral-cfd bench --config suites/normal.json`. See
[VALIDATION.md](VALIDATION.md) for building the pinned OpenLB installation.

The duct's current external correspondence remains incompatible. The smoke preset retains its
`INELIGIBLE` result and skips its JMH forks. The normal preset selects only periodic shear and uses the validation smoke suite, which excludes the duct. Exit code `0` requires
all selected cases/variants to pass; `4` indicates ineligible, unstable, insufficient-fork, or failed
results. Configuration errors return `2`; interrupted benchmark orchestration returns `130`.
Read the report rather than treating a nonzero suite exit as a failure of every backend.

## Stock workload and resources

The stock suite measures all 1,000 steps; `preSteps` is zero. Each of the four parallel variants uses
two independent forks, one warmup iteration and two measured iterations. JMH's one-second
iteration setting is a minimum duration, not a limit on a simulation: an invocation always
finishes its 1,000 steps. The first checked warmup qualifies each fork; there is no extra preflight simulation.
With one invocation per iteration, the suite runs the large simulation 25 times:
one parallel FJP reference plus 24 warmup/measurement runs across the four variants.
This is a sustained application comparison, and the complete suite can be a long campaign;
its wall time depends on the machine. The seven-day deadline bounds each child process tree,
not the whole suite. The replacement preset has been checked on bounded domains; its full-size
campaign has not been executed.

The configuration estimates roughly 4.83 GiB for population arrays, geometry and retained ranges;
the two population buffers alone use 4.75 GiB. Forks use `-Xms1g -Xmx12g`, and the simulation's
allocation budget is 8 GiB. Use a machine with at least 16 GiB of available RAM and additional
headroom for other processes. The streamed reference is approximately 3.125 GiB, retained once
and reused by all variants; allow at least 7 GiB of free output space for the reference and four final Euhedral snapshots,
in addition to validation artifacts and logs. Config inspection computes these sizes without allocating the population arrays.

Parallel variants use all available physical worker cores after reserving the driver core;
`workers`/`cpus` can cap this explicitly. Affinity requests are enabled. Every variant retains the
same 32-cubed range shape, 10-step diagnostic cadence, reset policy and timed work. Small heap
settings from smoke runs are unsuitable for the stock domain.

## Qualification and identity

Preflight either runs the configured `validationSuite` or reads `validationReport`; choose exactly
one. A retained report can be used without an installed OpenLB executable. The runner copies that
report into its output and hashes it before launching children.

Eligibility requires a passed, verified external case with frozen tolerance evidence and matching
numerical identity and the explicitly selected coverage scope. Numerical identity hashes the loaded configuration, frame,
geometry, and solver classes, including dirty development classes. Scheduling changes do not
invalidate that numerical coverage, but every new fork renews complete backend equivalence.
The full loaded CFD artifact identity is checked again in each child to detect changes during a run.
Old reports without these identity fields must be regenerated.

Physical identity includes grid, geometry and mesh contents, numerical/physical parameters,
boundaries, initial state, duration, precision convention, guards, and diagnostic cadence.
Backend settings, brick shape, deadlines, output paths, and memory budgets are excluded from
physical identity. They remain explicit execution dimensions. `validationScope` defaults to `EXACT`:
changing viscosity, a boundary, the duration, or diagnostics then requires the exact case's evidence.

The normal preset explicitly selects `PERIODIC_SHEAR_FAMILY`. This uses the bounded OpenLB shear
case as representative numerical coverage and permits only grid size and step count to change.
It requires lattice units, unforced shear, fully periodic faces, no obstacles or meshes, matching
viscosity, density, shear amplitude/modes, initial state, guards and diagnostics, plus verified
periodic-streaming/viscosity/transient coverage. A content identity enforces those requirements;
feature labels alone cannot qualify a case. The report's scope and retained external configuration
make clear that OpenLB did not validate the 256-cubed, 1,000-step trajectory itself. Complete Java
reference equivalence and runtime guards still run at the actual benchmark size and duration.
Other physical changes and nonperiodic cases retain the exact-case requirement.
A passing case can be used from a report that also contains an incompatible case; the latter
remains ineligible independently.

`referenceBackend` selects the backend that generates the complete field, independently of the
measured variants. It defaults to `serial` for custom suites; both supplied presets explicitly select
`fjp` and omit serial timing. No separate serial qualification replay runs. The external validation
gate checks numerical evidence before reference generation.

The reference process generates the actual benchmark-size reference
with the chosen backend and shared worker budget, using the benchmark's brick shape. It streams
all 19 populations, solid identities, derived fields and reductions to disk. Every scheduling
variant reuses this file. `reference/reference.json` records the backend, worker budget and
case/artifact identity; every JMH fork requires matching completed evidence before measuring.
The full reference remains outside timing, with no second population grid retained in the benchmark
JVM. Same-kernel equivalence alone does not establish external accuracy at a new size or duration.

The stock order is bounded OpenLB validation, the full parallel FJP reference, then two forks each
of Euhedral with one source, Euhedral with worker-count sources, FJP and static workers. Static workers are persistent Java threads with fixed contiguous range
assignments and a barrier each timestep; there is no task queue or work stealing. Progress output
announces reference preparation, each fork, worker/source counts, completion and process-log paths.
A serial timing variant is optional. Without it, single-worker parallel efficiency stays blank;
FJP-relative speedup and MLUPS are still reported.

## Measurement boundary

Each backend/fork runs sequentially in its own JVM, with one external JMH driver and one retained
backend instance. `CfdBenchmark.advance` executes exactly `stepsPerInvocation` steps and uses
`Mode.AverageTime`, reported in seconds per invocation.

Before each invocation, on the driver:

1. Zero both existing population buffers, restore the initial fields, and clear driver reductions.
2. Advance `preSteps` physical steps using the same retained backend.
3. Check the assigned worker budget.

The configured simulation duration must equal `preSteps + stepsPerInvocation`. The measured
interval includes dispatch/source selection, numerical work, boundaries, configured diagnostics,
terminal waits, deterministic reductions, and population swaps. It excludes process/worker startup,
geometry preprocessing, frame/task allocation, reset, pre-steps, and full-field comparison.
JMH invocation fixtures bracket those operations outside the average-time interval.

Fork setup checks configuration, artifact and reference identities without advancing the simulation.
Every warmup and measured invocation receives a complete reference comparison in invocation teardown.
Measurement requires a successfully checked warmup; there is no separate per-fork preflight pass.
A mismatch, incomplete invocation, changed artifact, worker-budget change, or process deadline
invalidates the fork. No sampling or checksum-only shortcut replaces a complete field comparison.

Reset and initial first-touch are driver-owned. Arrays, frame scratch, FJP tasks, and static workers
are retained. Euhedral policy/cache history remains continuous within a fork; resetting populations
does not call `ControlPlaneLattice.clear`. These policies are recorded, so a later locality/reset
experiment must remain a distinct comparison dimension. Locality sweeps belong to Phase 11.

Separate timings record setup, reset/pre-steps, full comparisons, visualization export and process elapsed time.
`invocationEndToEndNs` includes all invocation fixtures and checks across warmup and measurement;
`simulationAndExportEndToEndNs` describes reference generation and export. Neither is a steady-state
JMH score or an OpenLB speedup.

## Viewing the Euhedral result

Each successful Euhedral fork writes `simulation-final.vti` in its fork directory after all
measurement iterations and full-field checks finish. The terminal prints its path. Open this file
in ParaView, click Apply, and color by `velocity` (Magnitude) or `gauge_pressure`; use a Slice filter
to inspect the volume. The snapshot contains velocity, density, gauge pressure, solid mask and
obstacle IDs at the final simulation time. It is a single final state, not an animation.

Export uses the existing streaming VTI writer and the completed simulation buffers; it does not
rerun the solver or copy a full field into memory. There is no snapshot I/O inside timed invocations.
The normal preset writes four binary snapshots (two Euhedral variants times two forks), about
720 MiB each. `trial.json` records `visualizationFile` and `visualizationExportNs` separately from
JMH timings. Only completed, verified simulation states are exported.

## Terminal progress and ETA

The launcher forwards `[CFD progress]` lines from the retained process logs to the terminal while
children run. Reference generation, warmup and measurement passes report their
step count, percentage, elapsed time and approximate remaining duration every five seconds, plus
start/completion lines. A typical line is:

```text
[CFD progress] periodic-256/reference (fjp) | full reference | running | step 250/1000 (25.0%) | elapsed 00:02:00 | ETA ~00:06:00
```

ETA uses elapsed time per completed step in the current pass. It resets for every pass and initially
says `estimating`; it does not extrapolate one backend's rate into a whole-campaign ETA. Field reset,
comparison and export report elapsed time with ETA unavailable because they have no timestep count.
Stopped/incomplete passes are marked stopped, without a completion ETA.

Each fork retains one progress counter and a daemon reporter. The driver makes one opaque counter
store after each timestep; frame bodies and worker completion hooks allocate nothing for progress.
Formatting, console output and parent log forwarding happen outside the measured driver body.
The counter store remains part of measured dispatch/step overhead, and the reporter has a small
background cost. All variants use the same policy, recorded in trial metadata. This telemetry counter
does not publish simulation fields; synchronized phase transitions keep a pass reset and its label
consistent with reporter snapshots. Reporter and forwarding threads are closed at process/trial end.

## Suite settings

| Setting | Meaning |
|---|---|
| `validationSuite` / `validationReport` | One relative or absolute path to fresh validation inputs or retained evidence |
| `cases` | Unique ID, configuration path, external `validationCase` ID and optional `validationScope` (`EXACT` or `PERIODIC_SHEAR_FAMILY`) |
| `variants` | Unique ID, backend, and optional Euhedral `sources` integer or `"workers"` |
| `baselineVariant` | Variant used as the speedup numerator; presets select `fjp` |
| `referenceBackend` | Backend generating the full reference; default `serial`, supplied presets `fjp` |
| `workers`, `cpus`, `affinity` | Shared parallel physical-worker budget and optional affinity requests |
| `brick` | Common positive XYZ range dimensions for every variant, including serial |
| `preSteps`, `stepsPerInvocation` | Untimed physical preparation and exact timed work |
| `forks`, `warmupIterations`, `measurementIterations`, `iterationMillis` | Explicit JMH repetition/duration settings |
| `processDeadlineMillis` | Bounds each reference/JMH process tree, including startup and untimed checks; maximum seven days |
| `maxForkCv` | Maximum coefficient of variation across independent fork means |
| `jvmArgs` | Explicit JVM options applied to reference and benchmark children |
| `outputDirectory` | Parent for a new `benchmark-*` directory; previous runs are preserved |

Parallel variants share the same effective physical CPU set, excluding the driver/reserved core
where possible and counting logical siblings once. Serial uses one of those worker cores. Reports
retain requested and effective CPU IDs, affinity capability, worker counts, JVM arguments, precision,
fluid cells, range shape, source revision, and requested/resolved sources. Capability labels describe
the platform's placement support, not proof of exact placement.

The `euhedral-one` preset explicitly uses one source for the FJP comparison. `euhedral-workers`
uses one per effective worker. Additional positive source counts can be added as separate variants.
The driver submits round-robin without shuffling or assigning sources to workers. Source creation
occurs during setup; selection and submission occur inside timed dispatch. Source variants are never
pooled into a single Euhedral score.

## Artifacts and interpretation

Every eligible case retains its replayable configuration and complete reference. Each variant/fork retains
`job.json`, `command.json`, `process.log`, raw `jmh.json`, `trial.json`, and `result.json` when those
stages complete. Failed or timed-out forks retain their available artifacts and never acquire a
successful score. `report.json` marks whether the suite finished and contains per-case eligibility,
per-fork status and iteration values. Reports are published after each case so earlier evidence
survives interruption. The external report is retained separately from backend validity evidence.

`comparison.csv` and `comparison.md` keep source variants separate and use:

```text
MLUPS = fluidCells * stepsPerInvocation / (secondsPerInvocation * 1e6)
speedup = baselineSeconds / candidateSeconds
parallelEfficiency = serialSeconds / (workerCount * parallelSeconds)
```

Aggregate time is the mean of independent JVM fork means, with sample standard deviation and
coefficient of variation alongside it. Raw iteration values remain available. At least two complete
forks are required for variability qualification. The stock preset selects two forks to meet this minimum while limiting
campaign duration. A single-fork run is `INSUFFICIENT_FORKS`, and a
run exceeding `maxForkCv` is `UNSTABLE`. These statuses, failures, timeouts, and numerical ineligibility
remain outside derived performance comparisons. Completed raw measurements remain available for
inspection. Short integration runs verify the harness and do not establish a throughput winner.
