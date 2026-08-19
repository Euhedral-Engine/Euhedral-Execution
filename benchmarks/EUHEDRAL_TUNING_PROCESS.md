# Euhedral Calibration and Tuning Process

This document defines the general process you should follow when tuning Euhedral.

The goal is not to blindly search the decision surface. The goal is to isolate one physical relationship at a time, measure it, encode the result into the policy, and then re-test the coupled system.

Use the existing calibration harness, comparison pipeline, profiles, sweeps, and exported statistics. Prefer small controlled experiments over large multidimensional searches.

---

## 1. Core Rules

1. Throughput is authoritative.
   - Use JMH throughput to decide whether a policy change improved performance.
   - Telemetry explains why the result changed.
   - Do not create a synthetic score from occupancy, centroids, oscillation, correlations, or vector fields.

2. Change one major dimension at a time.
   - Body cost
   - Contention
   - Execution policy
   - Idle policy
   - Skip policy
   - Mixed workload behavior

3. Use coarse-to-fine experiments.
   - Start with a wide sweep.
   - Identify a crossover or useful region.
   - Narrow the sweep around that region.
   - Stop narrowing when the result becomes dominated by noise.

4. Prefer boundaries defined by behavior.
   - A band boundary should correspond to a meaningful change in system economics.
   - Do not force evenly spaced bands unless the data supports it.

5. Preserve repeatability.
   - Use JSON profiles and profile libraries for reusable fixtures.
   - Use sweeps for controlled variation.
   - Retain expanded trial configs.
   - Do not modify completed run artifacts.

6. Use system-level fork statistics for calibration.
   - `SystemForkResult` is the authoritative scheduler telemetry view for one independent JMH fork.
   - `SystemIterationResult` is for within-fork diagnostics.
   - `CoreIterationResult` is for investigating worker/core-specific anomalies.
   - Do not tune the global policy from individual core centroids or per-core normalized distributions unless diagnosing a specific topology problem.

7. Treat forks as independent replicates.
   - Do not merge separate forks into one physical telemetry population.
   - Use fork throughput distributions for comparison variance.

---

## 2. Important Body-Cost Concepts

These values are related but are not interchangeable.

### `workUnits`

Controls how much synthetic computation the executor performs.

It is an experiment input.

### Decision weights

Weights are portable calibration inputs used by the fragments to derive runtime body-cost thresholds.

They are not nanosecond thresholds and they are not equal to `workUnits`.

### `smoothedBodyCost`

The measured runtime body cost used by the policy.

It measures the executor invocation from entry to return, not only the synthetic work itself.

Therefore:

```text
workUnits != decision weight != smoothedBodyCost
```

When defining a body band, separate these two questions:

```text
1. What physical workload region should this band represent?
2. What portable weight causes runtime calibration to place the threshold at that region?
```

Do not infer the correct decision weight directly from `workUnits`.

### Current starting anchors

These are current calibration observations, not universal constants:

```text
workUnits 0:
    current XS weight reference ~= 96

DIRECT execution stops providing a throughput advantage:
    workUnits ~= 216
```

Re-measure when hardware, calibration behavior, or execution overhead changes.

---

## 3. Recommended Calibration Order

Follow this order unless experimental evidence gives a strong reason to deviate.

```text
body landmarks
    ->
body weight mapping
    ->
contention landmarks
    ->
execution policy surface
    ->
idle policy surface
    ->
execution re-check with idling enabled
    ->
skip policies
    ->
joint local refinement
    ->
mixed workloads
    ->
cross-machine validation
```

Do not begin with a full 5x5 policy search.

---

## 4. Phase 1 - Establish Body-Cost Landmarks

Define the body axis before tuning contention.

Use a deliberately simple topology:

```text
small fixed core set
small fixed source set
parallel sources only
randomizeWork = false
idle parking disabled
fixed execution policy
```

A two-core, two-source fixture is a good default because it avoids both extreme source starvation and a large abundance of productive handles.

### Find the minimum body reference

Use `workUnits = 0`.

Sweep a collapsed body threshold where all four thresholds use the same weight:

```text
xs = W
s  = W
m  = W
h  = W
```

The resulting body classification should fall on one side of the collapsed boundary.

Bracket and narrow the weight until the minimum workload is classified where desired.

This defines the practical XS reference.

### Find the upper body landmark

Run two matched sweeps:

```text
forced DIRECT
forced STAGED
```

Keep the topology and workload sequence identical.

Increase `workUnits`.

Use KEYED comparison so each DIRECT workload is compared only with the matching STAGED workload.

Look for the region where:

```text
DIRECT clearly wins
    ->
DIRECT advantage shrinks
    ->
DIRECT and STAGED are effectively equivalent
    ->
STAGED may begin to win
```

The meaningful landmark is the physical region where execution-path overhead stops being important relative to body work.

Do not zoom indefinitely. Once neighboring points become noisy and stop showing a reliable slope, choose a representative point from the last clean transition region.

### Fill intermediate body bands

After the minimum and upper landmarks are known, choose S, M, and H boundaries from additional physical behavior or useful separation in the body-cost distributions.

Do not assume equal spacing.

Prefer boundaries that divide observably different execution regimes.

---

## 5. Phase 2 - Map Weights to Runtime Body Thresholds

Once physical body landmarks are selected, separately determine the decision weights that reproduce them.

For each target landmark:

```text
fixed machine/core
fixed calibration fixture
fixed workload reference
sweep one decision weight
```

Record the resulting calibrated runtime threshold or resulting classification behavior.

Build the empirical mapping:

```text
decision weight -> calibrated runtime body threshold
```

Use bracketing and narrowing rather than a large blind grid.

If the mapping is nonlinear, preserve that behavior. Do not force a linear interpretation.

The final policy should store the portable weights that produce the desired physical body boundaries.

---

## 6. Phase 3 - Establish Contention Landmarks

Freeze the body bands before defining contention bands.

For a fixed body regime:

1. Disable idle parking.
2. Hold execution policy constant.
3. Sweep source availability/contention-producing conditions.
4. Observe exact execution decision occupancy and throughput.
5. Identify distinct contention regimes from actual behavior.

Useful controls include:

```text
parallelSources
orderedSources
worker/core count
productive handle availability
```

Do not assume that more sources means more contention.

For example, one source with several workers can create extreme acquisition contention because many workers compete for the same productive opportunity.

Define contention boundaries from observed system behavior, not from arbitrary equal spacing.

---

## 7. Phase 4 - Build the Execution Policy Surface

With body and contention bands reasonably stable, map DIRECT vs STAGED.

Keep idling disabled first.

For each useful body regime:

1. Sweep contention.
2. Run a forced-DIRECT fixture.
3. Run the matching forced-STAGED fixture.
4. Compare matching conditions using KEYED comparisons.
5. Identify the crossover region.
6. Narrow only around the crossover.

Encode only relationships that were actually observed.

Example form:

```text
body band M:

XS contention -> DIRECT
S contention  -> DIRECT
M contention  -> DIRECT
H contention  -> STAGED
XH contention -> not yet established
```

Do not fill unknown cells from symmetry or aesthetics.

### Important interpretation rule

STAGED does not need to reduce the measured contention band in order to be better.

If:

```text
contention remains high
+
STAGED throughput is higher
```

then STAGED may simply tolerate or organize work under contention better.

Throughput determines the winner.

Occupancy explains the regime where the winner occurred.

---

## 8. Phase 5 - Calibrate Idle Behavior

After the no-idle execution surface is understood, freeze it temporarily and tune participation/idling.

For a fixed execution policy region:

1. Start with no parking.
2. Sweep park duration.
3. Measure throughput.
4. Observe contention occupancy and transition behavior.
5. Find the progression:

```text
under-idled
    ->
useful plateau / optimum
    ->
over-idled
```

Do not assume the best park duration is the one that reduces contention the most.

Parking may improve throughput through phase alignment or reduced collision frequency even when the contention signal remains high.

Tune idle thresholds and durations by body and contention region only when the data supports separate behavior.

---

## 9. Phase 6 - Re-check Execution With Idling Enabled

Idling changes contention and therefore changes the economics of DIRECT vs STAGED.

After an idle surface is selected:

1. Re-run the execution crossover experiments.
2. Keep the idle policy fixed.
3. Re-check DIRECT/STAGED boundaries.
4. Update only boundaries whose crossover materially moved.

The no-idle execution map is a physical baseline, not necessarily the final closed-loop policy.

---

## 10. Phase 7 - Calibrate Skip Policies

Treat skip as a transitory phase-realignment action, not a stable execution state.

Available behaviors:

```text
SKIP_THEN_DIRECT
SKIP_THEN_STAGED
```

Use skip only after DIRECT, STAGED, and idle behavior are reasonably understood.

Test skip in regions where:

```text
contention is severe
normal staging/direct execution is still inefficient
phase realignment may help
```

Compare:

```text
DIRECT
STAGED
SKIP_THEN_DIRECT
SKIP_THEN_STAGED
```

under otherwise identical conditions.

Remember that repeated skipping can indirectly change batch behavior by extending batches and affecting the existing batch-size controller.

Do not create a policy that can remain in SKIP indefinitely.

---

## 11. Phase 8 - Joint Local Refinement

Once the major branches are established, perturb neighboring cells around high-performing regions.

Do not search the entire matrix.

Focus on boundaries where:

```text
throughput differences are small
occupancy frequently crosses the boundary
transition analysis shows oscillation
the vector field suggests repeated movement between neighboring cells
```

Test small local alternatives and retain changes only when throughput improves or becomes more stable without a meaningful throughput loss.

---

## 12. Reading the Telemetry

### Occupancy

Use exact 5x5 occupancy to determine where the scheduler actually spends time.

Important values:

```text
cell probabilities
contention centroid
body centroid
variance
covariance
radius
```

Do not optimize centroid position directly.

### Transitions

Use transition matrices to distinguish:

```text
stable occupancy
drift
boundary crossing
A <-> B oscillation
```

High self-transition can indicate stability but is not automatically good.

### Vector fields

Use vector fields to understand local movement through the state surface.

A productive attractor may show:

```text
high occupancy
small local movement
restoring vectors around the region
limited oscillation
```

But an attractive-looking vector field is not a substitute for throughput.

### Correlations

Use Pearson and Spearman results to generate hypotheses.

Do not interpret correlation as causation.

Use an interesting correlation to design the next controlled experiment.

### Head vs steady state

Use:

```text
head
    startup / convergence behavior

steadyState
    sustained settled behavior
```

Do not treat the rolling steady-state sample as a literal end-of-work tail.

---

## 13. Comparison Strategy

Use the comparison mode that matches the experiment.

### BASELINE

Use when testing many candidates against one fixed reference.

```text
baseline
    vs candidate A
    vs candidate B
    vs candidate C
```

### KEYED

Preferred for sweep-vs-sweep experiments.

Examples:

```text
DIRECT(24) vs STAGED(24)
DIRECT(48) vs STAGED(48)
DIRECT(96) vs STAGED(96)
```

Prefer keys from resolved `TrialConfig`, such as:

```text
/origin/candidateIndex
```

or a compound key containing the actual swept dimensions.

### CROSS

Use only when the Cartesian comparison itself is meaningful.

Expect many CROSS pairs to be incompatible when workload dimensions differ.

Do not use CROSS as a replacement for keyed matching.

---

## 14. Experiment Construction

Prefer reusable profile libraries.

Keep stable fixtures in:

```text
calibrationProfiles
decisionWeightProfiles
```

Use experiment JSON primarily to describe:

```text
the hypothesis
the controlled variable
the sweep
the trial relationship
artifact retention
```

A good experiment should make it obvious what changed.

If a trial requires large repeated inline configuration blocks, consider moving the stable portions into a profile library.

---

## 15. Search Discipline

Do not blind-search the complete decision space.

Use this loop:

```text
form hypothesis
    ->
build narrow controlled sweep
    ->
run
    ->
compare
    ->
inspect system telemetry
    ->
identify crossover / slope / anomaly
    ->
narrow or change one dimension
    ->
encode proven relationship
```

Use logarithmic/coarse spacing when the scale is unknown.

Use binary-style bracketing when locating a threshold.

Use dense local sweeps only after a useful region has been identified.

Stop narrowing when the observed slope disappears into run-to-run noise.

---

## 16. When Results Look Strange

Do not immediately tune around unexpected data.

First determine whether it exposed a hidden system behavior or a harness/configuration mistake.

Check:

```text
expanded trial_config.json
comparison compatibility
actual sweep paths
body vs idle weight field
DIRECT/STAGED policy matrix
observation toggles
system FORK occupancy
fork-to-fork variance
```

Unexpected assumptions often expose useful implementation and measurement bugs.

A strange result is often worth investigating before discarding it.

---

## 17. Mixed Workloads

Do not begin with randomized/mixed body work.

First establish the uniform surface.

Then test:

```text
randomized body work
mixed source types
ordered + unordered sources
realistic workloads
Mandelbrot or other variable-cost workloads
```

If mixed workloads with similar contention/body occupancy require materially different policies, the current state representation may be missing a variable.

Possible later variables include:

```text
body-cost variance
contention variance
transition volatility
```

Do not add another policy dimension until the 2D model demonstrably fails.

---

## 18. Validation Across Machines

Weights are intended to be portable calibration inputs, while runtime thresholds are hardware-specific.

After tuning on one machine:

1. Re-run the calibration fixtures on another architecture.
2. Confirm body-weight calibration produces sensible physical thresholds.
3. Confirm major policy relationships still hold.
4. Re-tune portable weights only if needed.
5. Avoid hardcoding architecture-specific policy branches unless repeated evidence requires them.

Allow per-core calibration to account for heterogeneous cores naturally where possible.

---

## 19. Definition of Done for a Tuning Step

A tuning step is complete when:

```text
the experimental variable was isolated
the comparison was compatible
the throughput relationship is repeatable enough to act on
the relevant system occupancy was actually exercised
the chosen boundary/action has a physical explanation
the result is encoded in a reusable decision-weight profile
the expanded configuration is retained
```

Do not require perfect certainty.

Prefer a stable, interpretable boundary over chasing the last noisy percentage point.

---

## 20. Overall Tuning Flow

Use this as the default process:

```text
1. Define minimum body reference.
2. Find body workload landmarks from execution-path economics.
3. Map portable weights to those physical body landmarks.
4. Freeze body bands.
5. Establish contention regimes.
6. Map DIRECT/STAGED by body x contention.
7. Tune idle participation separately.
8. Re-check DIRECT/STAGED with idling active.
9. Evaluate skip actions only in severe regions.
10. Refine neighboring cells around real crossovers.
11. Validate uniform workloads.
12. Validate mixed/variable workloads.
13. Validate on additional hardware.
14. Update reusable profiles with proven relationships.
```

The objective is not to discover a mathematically elegant surface.

The objective is to build the simplest policy surface that consistently produces the best measured throughput across the physical regimes Euhedral actually occupies.
