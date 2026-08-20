# Euhedral Calibration and Tuning Process

This document defines the general process you should follow when tuning Euhedral.

The goal is not to blindly search the decision surface. The goal is to isolate one physical
relationship at a time, measure it, encode the result into the policy, and then re-test the coupled
system.

Use the existing calibration harness, comparison pipeline, profiles, sweeps, and exported
statistics. Prefer small controlled experiments over large multidimensional searches.

---

## 1. Core Rules

1. Throughput is authoritative.
    - Use JMH throughput to decide whether a policy change improved performance.
    - Telemetry explains why the result changed.
    - Do not create a synthetic score from occupancy, centroids, oscillation, correlations, or
      vector fields.

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
    - Do not tune the global policy from individual core centroids or per-core normalized
      distributions unless diagnosing a specific topology problem.

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

Weights are portable calibration inputs used by the fragments to derive runtime body-cost
thresholds.

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

A two-core, two-source fixture is a good default because it avoids both extreme source starvation
and a large abundance of productive handles.

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

The meaningful landmark is the physical region where execution-path overhead stops being important
relative to body work.

Do not zoom indefinitely. Once neighboring points become noisy and stop showing a reliable slope,
choose a representative point from the last clean transition region.

### Fill intermediate body bands

After the minimum and upper landmarks are known, choose S, M, and H boundaries from additional
physical behavior or useful separation in the body-cost distributions.

Do not assume equal spacing.

Prefer boundaries that divide observably different execution regimes.

---

## 5. Phase 2 - Map Weights to Runtime Body Thresholds

Once physical body landmarks are selected, separately determine the decision weights that reproduce
them.

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

The final policy should store the portable weights that produce the desired physical body
boundaries.

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

For example, one source with several workers can create extreme acquisition contention because many
workers compete for the same productive opportunity.

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

After the no-idle execution surface is understood, freeze it temporarily and tune
participation/idling.

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

Parking may improve throughput through phase alignment or reduced collision frequency even when the
contention signal remains high.

Tune idle thresholds and durations by body and contention region only when the data supports
separate behavior.

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

Remember that repeated skipping can indirectly change batch behavior by extending batches and
affecting the existing batch-size controller.

Do not create a policy that can remain in SKIP indefinitely.

---

## 11. Phase 8 - High-Contention Zoom

Phase 7 established that the unresolved policy competition is concentrated in the upper contention
range.

Current broad behavior:

below approximately 65% contention:
DIRECT remains dominant

high contention:
the meaningful comparison is primarily:
STAGED SKIP_THEN_STAGED

Do not rerun the full policy surface.

Focus experimental resolution on approximately:

65% - 100% measured contention

The current bands are:

M:   35% - 65% H:   65% - 85% XH:  85% - 100%

### Build a dense local contention surface

Generate several fixtures that occupy meaningfully different locations within the 65%-100% range.

Useful controls include:

parallelSources orderedSources worker/core count productive source availability

Use actual measured contention as the physical variable.

Do not treat source count itself as the final independent variable.

For each useful contention fixture:

1. Run or reuse STAGED.
2. Run SKIP_THEN_STAGED.
3. Cover all body bands initially.
4. Record actual steady-state contention/body occupancy.
5. Compare matched conditions.
6. Identify where winner, margin, or slope changes.

Reuse existing Phase 7 results whenever the fixture is equivalent.

Do not rerun DIRECT or SKIP_THEN_DIRECT unless new evidence specifically requires them.

### Zoom strategy

Prefer:

moderately dense 65%-100% sweep ->
identify transition regions ->
fine-grained local sweep ->
multi-fork verification

Do not probe one contention point at a time.

The purpose is to discover the shape of the STAGED vs SKIP_THEN_STAGED surface.

Possible outcomes include:

STAGED always wins SKIP_THEN_STAGED owns a stable subregion multiple crossovers exist body band
changes the crossover topology changes the crossover despite similar measured state

Do not assume monotonic behavior.

### Completion gate

Phase 8 is complete when:

- the H/XH contention region has substantially better resolution
- likely STAGED / SKIP_THEN_STAGED crossover regions are identified
- candidate gains are replicated
- any body-dependent behavior is characterized
- low/mid contention has not been unnecessarily rerun

---

## 12. Phase 9 - Restore Productive-Handle Telemetry and Reframe the High-Contention Model

Phase 8 is complete.

Its main discovery is that scalar contention alone does not fully explain the high-contention policy
surface.

The strongest unresolved relationship is now:

STAGED vs SKIP_THEN_STAGED

inside high-contention regions where worker count, productive-handle availability, and hardware
topology differ.

Do not immediately refine contention thresholds further.

First restore the missing productive-handle signal.

### Restore productive-handle accounting from git history

The productive-handle count existed before the recent refactor.

Use git history to recover the previous implementation and understand:

- where productive handles were counted
- what qualified a handle as productive
- when the count was updated
- whether the value was per-worker, per-fragment, or global
- how registered worker count was incorporated
- where the old strict threshold influenced policy

Do not blindly revert the old implementation.

Extract only the useful measurement semantics and integrate them into the current architecture.

Preserve the current decision-tree and calibration structures.

### Define the productivity signal

The important distinction is:

contention:
failed lock acquisitions / total lock acquisition attempts

productivity:
whether a successful handle acquisition actually produced useful work

These represent different physical properties.

Contention answers:

    how difficult was it to obtain access?

Productivity answers:

    when access was obtained, was useful work available?

Restore enough telemetry to measure both independently.

A useful normalized runtime quantity is:

    productiveHandleRatio =
        productiveHandles
        /
        registeredWorkers

Cap or normalize only if needed for representation.

Do not assume the previous strict productive-handle threshold is still appropriate.

The ratio is now the object of study.

### Instrument before controlling

At this phase, productivity is explanatory telemetry only.

Do not immediately add it as:

- a third decision-tree axis
- a hard threshold
- a composite pressure formula
- a replacement for contention

Record it alongside:

contention body cost worker count source topology execution policy idle behavior throughput

### Re-analyze Phase 8 geometry

Use existing Phase 8 artifacts where possible.

For each high-contention fixture, determine or reproduce the corresponding productive-handle ratio.

Pay particular attention to:

4 workers / 4 productive handles 4 workers / 3 productive handles 8 workers / 4 productive handles 8
workers / 2 productive handles 4 workers / 1 productive handle 8 workers / 1 productive handle

The purpose is to determine whether the observed SKIP_THEN_STAGED region aligns more cleanly with:

contention alone

or

contention + productiveHandleRatio

### Hardware-topology caution

Phase 8 used both P-core and E-core fixtures.

The E-core fixtures may share cache or cluster resources in ways that materially affect the observed
SKIP_THEN_STAGED gains.

Treat those results as:

real replicated topology-specific

until the cache/core-cluster relationship is explicitly accounted for.

Do not use E-core results alone to define portable policy boundaries.

### Completion gate

Phase 9 is complete when:

- productive-handle accounting is restored
- the old implementation has been inspected from git history
- productiveHandleRatio is available in telemetry
- contention and productivity are clearly separate measurements
- Phase 8 fixtures can be interpreted in terms of both quantities
- no new policy axis has been introduced yet

---

## 13. Phase 10 - Map the Contention x Productivity Surface

Once productiveHandleRatio is observable, determine whether it explains the high-contention behavior
discovered in Phase 8.

Do not begin with a formula.

Measure the physical surface first.

### Core hypothesis

SKIP_THEN_STAGED may be useful when:

contention is high

+

multiple productive opportunities still exist

+

workers outnumber productive handles

This would create a regime where phase realignment can redistribute access to useful work.

At the extremes:

high productivity / sufficient productive handles:
skipping may waste opportunities

very low productive ratio:
skipping cannot create additional productive opportunities STAGED may regain the advantage

This is a hypothesis, not a conclusion.

### Experimental dimensions

Use a bounded set of fixtures spanning:

productiveHandleRatio near 1.0 productiveHandleRatio around 0.75 productiveHandleRatio around 0.5
productiveHandleRatio around 0.25 very low productiveHandleRatio

while also spanning the useful high-contention region.

Do not use source count as the final physical variable.

Source count is only a control used to produce:

measured contention measured productiveHandleRatio

### Policies

Focus primarily on:

STAGED SKIP_THEN_STAGED

Reuse DIRECT evidence from earlier phases.

Do not rerun DIRECT unless the new productivity-aware fixture changes the runtime condition enough
that prior evidence is incompatible.

SKIP_THEN_DIRECT remains out of active refinement unless new evidence specifically revives it.

### Body coverage

Cover all body bands initially.

Body may determine whether a given contention/productivity state benefits from skipping.

Do not collapse body dependence prematurely.

### Analysis

For each matched condition record:

throughput contention productiveHandleRatio body band worker count core topology occupancy
transition behavior policy winner relative delta

Look for whether policy winner follows a surface such as:

low contention:
DIRECT

high contention + high productive ratio:
STAGED

high contention + moderate productive deficit:
SKIP_THEN_STAGED

extreme contention + very low productive ratio:
STAGED

Do not force this shape if the data disagrees.

### Completion gate

Phase 10 is complete when:

- the useful high-contention space has coverage across productivity ratios
- STAGED vs SKIP_THEN_STAGED behavior is mapped against both signals
- repeated crossovers are replicated
- E-core and P-core behavior are compared separately
- it is clear whether productiveHandleRatio explains variance that contention alone could not

---

## 14. Phase 11 - Determine Whether Contention and Productivity Can Be Compressed

Only after the 2D contention/productivity surface is understood should a composite signal be
considered.

Do not invent a formula first.

The objective is to determine whether:

    f(contention, productiveHandleRatio)

can preserve the relevant policy boundaries.

### Requirements for compression

A composite signal is justified only if:

- similar composite values imply similar preferred actions
- STAGED/SKIP_THEN_STAGED crossover geometry collapses cleanly
- topology-specific effects are not being hidden
- body interactions remain representable
- the new scalar performs at least as well as the two-signal interpretation

Potential conceptual structure:

effective acquisition pressure = contention adjusted by productive opportunity

But do not assume addition, multiplication, or any specific transform.

Use the measured surface to infer the shape.

Candidate approaches may include:

simple ratio transforms piecewise functions logistic transforms small regression models decision
boundaries

Use Apache Commons Math where appropriate.

The production implementation should remain cheap.

### Prefer interpretability

If the relationship is simple enough to express directly, prefer that over a learned model.

A small explicit function is preferable to introducing ML into the hot control loop.

### Do not force compression

If contention and productiveHandleRatio independently encode necessary information, retain them as
separate control inputs.

Compression is an optimization, not a requirement.


---

## 15. Phase 12 - Rebuild the High-Contention Thresholds

Only after productivity has been incorporated into the analysis should the Phase 8 candidate
contention thresholds be finalized.

Current Phase 8 candidate vector:

    [650000, 800000, 900000, 970000]

Treat this as provisional.

Re-evaluate whether these thresholds remain useful once productiveHandleRatio explains part of the
observed behavior.

Ask:

Does a contention threshold itself mark a policy transition?

or:

Did that apparent transition actually correspond to a change in productiveHandleRatio?

### Redistribute resolution only where useful

If contention below approximately 65% remains behaviorally uniform:

keep that region coarse

If meaningful behavior is concentrated above it:

retain finer resolution there

But do not spend contention tiers distinguishing regions that productivity already separates more
cleanly.

### Body dependence

Perform this analysis across body bands.

A contention/productivity boundary may be body-dependent.

Do not collapse body resolution unless all control actions support doing so.


---

## 16. Phase 13 - Build the Combined Effective Policy

Combine:

execution path idle duration skip behavior contention productiveHandleRatio body cost

into the smallest supported control policy.

Do not think in terms of independently filling multiple 5x5 tables.

For each physical regime determine the effective action.

Example conceptual form:

low acquisition contention:
DIRECT no idle no skip

high contention high productive availability:
STAGED bounded idle no skip

high contention moderate productive deficit:
SKIP_THEN_STAGED calibrated idle

near-total productive starvation:
STAGED calibrated idle

These are examples only.

Encode only observed relationships.

### Evidence reuse rule

Before any benchmark is scheduled:

1. Search existing artifacts.
2. Reuse exact compatible results.
3. Reuse compatible DIRECT/STAGED baselines from earlier phases.
4. Run only missing policy legs.
5. Replicate only candidate policy changes or unstable regions.

Do not reconstruct previously established surfaces.


---

## 17. Phase 14 - Joint Local Refinement

After the major contention/productivity/action relationships are established, refine only real
boundaries.

Focus on regions where:

policy winner changes throughput delta changes slope productiveHandleRatio crosses a candidate
boundary contention crosses a candidate boundary body changes the winner occupancy repeatedly
crosses a boundary transition behavior oscillates similar measured state produces different results
on different core topologies

Possible local variables:

contention threshold productiveHandleRatio threshold body threshold idle duration STAGED vs
SKIP_THEN_STAGED assignment

Change one relationship at a time.

### Zoom rule

Use:

coarse surface ->
identify boundary ->
dense local sweep ->
multi-fork replication ->
encode

Do not incrementally probe isolated points when a bounded local surface can be generated
declaratively.

Stop narrowing when the local slope disappears into benchmark variance.


---

## 18. Phase 15 - Evaluate Tier and State Simplification

Only after all active controls are understood should the representation be simplified.

For every existing boundary and state variable ask:

Does this distinction ever change:

execution path? idle duration? skip behavior? another meaningful control response?

If not, it may be removable.

### Evaluate all signals together

Do not remove body tiers because DIRECT/STAGED ignores them.

Do not remove contention tiers because productivity explains some high-contention behavior.

Do not retain productiveHandleRatio merely because it was useful experimentally if the final
composite policy makes it redundant.

The final state representation should be determined by the union of information required by all
actions.

### Possible outcomes

The final controller may use:

fewer contention tiers

or:

finer high-contention tiers

or:

contention + productivity

or:

a composite pressure signal

or:

different simplified conditions for different actions

Do not force all actions to share identical quantization.

### Regression requirement

Any structural simplification must be benchmarked against the previously calibrated policy before
adoption.


---

## 19. Phase 16 - Validate Uniform Workloads

After the effective policy and state representation are stable, validate representative uniform
workloads.

Cover all body regimes and representative combinations of:

low contention / high productivity high contention / high productivity high contention / moderate
productivity high contention / low productivity extreme contention / starvation

Verify:

throughput occupancy productive ratio behavior idle interaction skip frequency batch-size behavior
policy stability

Do not tune aggressively against validation workloads.

Use failures to identify missing relationships.


---

## 20. Phase 17 - Validate Mixed and Dynamic Workloads

Move beyond static fixtures.

Include:

mixed body costs randomized work changing productive-handle availability changing source
availability ordered and parallel mixtures bursty ingestion contention transitions productivity
transitions

Observe:

boundary oscillation idle overreaction repeated skip activation stale contention stale productivity
batch-size feedback recovery after state transitions

Pay particular attention to cases where:

contention stays similar but productivity changes

and:

productivity stays similar but contention changes

These tests validate that the two measurements represent genuinely different physical conditions.

Throughput remains authoritative.

Telemetry explains failures.


---

## 21. Phase 18 - Validate Hardware and Cache Topology

Revisit the P-core/E-core differences seen in Phase 8.

Before treating them as generic hardware variation, characterize the relevant topology:

core type shared cache boundaries E-core clustering socket placement SMT relationships NUMA
placement where relevant

### Controlled topology comparison

Construct fixtures where worker count and productiveHandleRatio are similar but cache/core topology
differs.

Compare:

P-core only E-core within one shared cluster E-cores across clusters if possible mixed core types
only if production may use them

Determine whether the large E-core SKIP_THEN_STAGED gains are driven primarily by:

contention/productivity state

or:

cache/core topology

or:

an interaction between them

Do not bake E-core-specific behavior into a portable baseline until this is understood.


---

## 22. Phase 19 - Validate Additional Machines

Once the control relationships are stable on the current host, test representative additional
hardware.

Do not recalibrate everything immediately.

Compare transferability of:

body weight -> runtime threshold contention measurement productiveHandleRatio
contention/productivity policy surface idle timing STAGED vs SKIP_THEN_STAGED region cache-topology
sensitivity

Classify each relationship as:

portable hardware-scaled topology-specific hardware-specific

Only add hardware-dependent calibration where the data demonstrates it is necessary.


---

## 23. Phase 20 - Freeze and Simplify the Production Policy

Once workload, topology, and hardware validation are complete:

1. Remove branches that never demonstrated useful behavior.
2. Collapse tiers that do not influence any action.
3. Preserve or increase resolution only where real crossover behavior exists.
4. Decide whether contention and productivity remain separate or can be safely compressed.
5. Remove experimental telemetry from the hot path if it is not required for production decisions.
6. Encode only repeatable relationships.
7. Record the provenance of each retained threshold and action.
8. Preserve all calibration artifacts unchanged.

The production controller should contain only the complexity justified by observed physical
behavior.

The calibration harness should remain richer than the production controller.

It should continue to expose contention, productivity, body behavior, topology, and policy outcomes
independently so future scheduler behavior can be investigated without redesigning the experimental
system.

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

If a trial requires large repeated inline configuration blocks, consider moving the stable portions
into a profile library.

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

If mixed workloads with similar contention/body occupancy require materially different policies, the
current state representation may be missing a variable.

Possible later variables include:

```text
body-cost variance
contention variance
transition volatility
```

Do not add another policy dimension until the 2D model demonstrably fails.

---

## 18. Validation Across Machines

Weights are intended to be portable calibration inputs, while runtime thresholds are
hardware-specific.

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

The objective is to build the simplest policy surface that consistently produces the best measured
throughput across the physical regimes Euhedral actually occupies.
