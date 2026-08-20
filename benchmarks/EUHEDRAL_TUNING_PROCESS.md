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

````markdown
## 14. Phase 11 - Stabilize Policy-Conditioned State and Identify Dynamic Effects

Phase 10 established that `productiveHandleRatio` is a real independent description of opportunity geometry, but it did not produce a stable static policy boundary.

The more important finding was that `STAGED` and `SKIP_THEN_STAGED` frequently drove the scheduler into materially different contention/body state populations.

Therefore, do not add another threshold, another decision-tree axis, or a composite pressure signal yet.

Phase 11 must determine whether the remaining policy differences are:

1. measurement/run variance;
2. performance differences at approximately equivalent runtime state;
3. benefits caused by the policy deliberately moving the scheduler into a different state distribution.

This distinction is required before further policy design.

### Treat Phase 10 as the starting evidence

Do not rerun the full Phase 10 surface.

Reuse:

- Experiment 23 surface artifacts;
- Experiment 24 fork-aware replication artifacts;
- exact occupancy distributions;
- transition matrices;
- contention/body centroids;
- `productiveHandleRatio`;
- independent JMH fork means;
- policy-conditioned throughput.

The fork-aware comparison artifacts are authoritative.

Ignore older comparison outputs that incorrectly collapsed multiple JMH forks into one sample for statistical conclusions.

### Define the identification problem explicitly

The remaining question is not simply:

```text
At state S, is STAGED or SKIP_THEN_STAGED faster?
````

because Phase 10 showed:

```text
initial state
+
policy
->
different steady-state population
->
different throughput
```

A policy comparison can therefore represent either:

```text
A. same-state action economics

or

B. beneficial/harmful state movement caused by the action
```

Phase 11 must distinguish these cases.

Do not treat policy-induced state movement as invalid behavior.

If `SKIP_THEN_STAGED` deliberately changes contention dynamics and thereby improves throughput, that is potentially the control mechanism itself.

The mistake would be interpreting that result as a static boundary such as:

```text
contention = 82% -> SKIP_THEN_STAGED
```

when the action does not remain near 82% after being selected.

### Build a state-comparability classification

Extend the analysis layer so matched policy comparisons can be classified by how similar their resulting runtime state distributions are.

At minimum compare:

```text
productiveHandleRatio
contention centroid
body centroid
contention/body occupancy distribution
occupancy total variation distance
dominant state
dominant self-transition rate
transition matrix behavior
```

Use existing exact aggregated telemetry.

Do not invent a single synthetic "state similarity score" unless necessary.

Prefer explicit component metrics.

Classify comparisons into categories such as:

```text
STATE_COMPARABLE

STATE_SHIFTED

STATE_DIVERGENT
```

The exact names may differ.

The classification should be based on empirical tolerances derived from the existing data rather than arbitrary precision requirements.

A starting interpretation could be:

```text
STATE_COMPARABLE:
    productive ratio equivalent
    body population equivalent
    contention population reasonably close
    occupancy TV distance small

STATE_SHIFTED:
    same opportunity geometry
    but policy causes a noticeable contention/occupancy shift

STATE_DIVERGENT:
    policies settle into substantially different state populations
```

Do not make the initial thresholds permanent policy constants.

They are analysis criteria.

### Use the Phase 10 clean result as the reference case

The strongest interpretable Phase 10 comparison was:

```text
8 E-cores
productiveHandleRatio = 0.125
XH body

STAGED:
    12.270M ops/s

SKIP_THEN_STAGED:
    12.015M ops/s

delta:
    STAGED +2.07%

occupancy TV distance:
    0.074

dominant state:
    24 for both policies

dominant self-transition:
    0.978 vs 0.968
```

Treat this as an example of approximately same-state policy comparison.

Use it to understand what a well-controlled policy comparison looks like.

Do not assume its exact TV distance is the universal cutoff.

### Identify strongly state-shifted Phase 10 cases

Use the existing Phase 10 artifacts to identify comparisons where policy changes the runtime state substantially.

Examples already observed include:

```text
P-core / ratio 0.25 / XS:
    TV = 0.675

P-core / ratio 0.25 / S:
    TV = 0.750

E-core / ratio 0.25 / XS:
    TV = 0.818

E-core / ratio 0.50 / M:
    TV = 0.875
```

For these cases, analyze:

```text
STAGED state distribution
SKIP_THEN_STAGED state distribution
transition matrices
vector fields
contention centroid movement
body centroid movement
throughput delta
```

Determine whether the action produces a repeatable directional state displacement.

The key question is:

```text
Does SKIP_THEN_STAGED consistently move the system toward a different attractor?
```

Do not assume that different means better.

### Separate steady-state location from transition dynamics

Two policies may have similar centroids but very different motion.

For each focused comparison inspect:

```text
occupancy
transition matrix
self-transition probability
oscillation indicators
vector field
contention displacement
body displacement
```

Look for patterns such as:

```text
STAGED:
    highly sticky high-contention attractor

SKIP_THEN_STAGED:
    lower self-transition
    stronger movement away from the attractor
```

or the reverse.

A one-cycle skip is specifically intended to alter phase alignment, so transition behavior may carry more information than a static centroid.

Do not reduce the analysis to mean contention.

### Create a minimal targeted benchmark set

After re-analyzing existing artifacts, run only the minimum new experiments necessary to answer unresolved questions.

Do not generate another broad Cartesian surface.

Select a small set of representative cases from Phase 10:

```text
one clean STATE_COMPARABLE case

one strongly STATE_SHIFTED case where STAGED appears better

one strongly STATE_SHIFTED case where SKIP_THEN_STAGED appears better or previously appeared better

one high-variance/inconclusive case
```

Prefer P-core fixtures where possible to reduce E-core/cache-topology confounding.

Use E-core cases only when they are specifically needed to test topology sensitivity.

Use at least 3 independent forks for all Phase 11 performance claims.

Reuse existing fork-aware results when sufficient.

### Improve fixture stability where possible

The purpose of the new targeted runs is not to force contention to an exact percentage.

Instead, reduce uncontrolled variation so policy-conditioned dynamics can be interpreted.

Keep fixed:

```text
cpuSet
worker count
productive handle count
productiveHandleRatio
body workload
randomizeWork = false
idle policy
JVM configuration
source type
ordered/parallel behavior
```

Avoid mixed P-core/E-core fixtures.

Avoid changing more than one physical dimension at a time.

If host isolation, affinity, background-load control, or benchmark ordering can reduce fork variance, use them.

Do not alter scheduler behavior merely to make the benchmark easier to interpret.

### Analyze each JMH fork independently before pooling

Phase 10 exposed a parser problem that previously hid true fork variation.

For Phase 11:

1. retain each JMH fork as an independent replicate;
2. compute one throughput mean per fork;
3. retain per-fork state telemetry;
4. compare whether the same policy-conditioned state movement appears across forks;
5. pool only after fork-level consistency is understood.

A policy-state effect is much stronger if:

```text
fork 1:
    same state displacement

fork 2:
    same state displacement

fork 3:
    same state displacement
```

than if aggregate telemetry hides three different behaviors.

Add or extend artifacts if necessary so fork-level occupancy and transition evidence can be inspected directly.

### Determine whether skip creates a repeatable state transition

For each targeted fixture classify the result into one of these outcomes.

#### Outcome A - No reliable policy difference

```text
throughput inconclusive
state movement inconsistent
fork behavior inconsistent
```

Interpretation:

No policy relationship is established.

Do not encode anything.

#### Outcome B - Same-state performance difference

```text
state populations comparable
throughput winner replicates
```

Interpretation:

There is a genuine local action-cost difference between `STAGED` and `SKIP_THEN_STAGED`.

This can potentially become a normal decision boundary.

#### Outcome C - Repeatable beneficial state movement

```text
policies settle into different states
state displacement repeats across forks
SKIP_THEN_STAGED produces higher throughput
```

Interpretation:

Skip is useful as a dynamic control action because it changes the scheduler trajectory.

Do not encode this as a static contention threshold yet.

Document:

```text
starting regime
action
resulting state movement
throughput effect
```

#### Outcome D - Repeatable harmful state movement

```text
SKIP_THEN_STAGED changes state
throughput decreases
```

Interpretation:

The skip action destabilizes or pushes the system toward a worse attractor in that regime.

Document this as an exclusion region.

### Do not introduce a new state axis yet

Even if `productiveHandleRatio` correlates with the dynamic behavior, Phase 11 must not immediately add:

```text
contention x productivity x body
```

as a production tree.

Likewise, do not create:

```text
effectivePressure = f(contention, productivity)
```

yet.

The immediate problem is determining whether the controller is primarily selecting actions based on state or managing transitions between states.

That distinction comes first.

### Do not refine contention thresholds yet

Keep the current candidate execution contention thresholds available for experimentation:

```text
[650000, 800000, 900000, 970000]
```

but do not promote them to final boundaries during Phase 11.

Phase 10 showed that the policy itself can move contention substantially.

A more precise static contention threshold is not useful until the dynamic behavior is understood.

### Preserve productiveHandleRatio telemetry

Regardless of whether productivity becomes a production control input, retain it in the calibration harness.

Phase 10 established that it measures a real physical property distinct from contention.

It remains valuable for:

```text
fixture characterization
state interpretation
cross-topology comparison
future policy research
ML/research datasets
```

Do not remove it merely because Phase 10 did not establish a policy threshold.

### Revisit the Phase 8 skip interpretation

Update the interpretation of Phase 8 using Phase 10/11 evidence.

The earlier apparent:

```text
80% - 90% contention
->
SKIP_THEN_STAGED
```

relationship is no longer established as a static policy band.

The revised interpretation should be:

```text
Phase 8 discovered a region where skip could materially change throughput.

Phase 10 showed that:
    those effects were not reliably determined by contention alone
    productive ratio alone was also insufficient
    policy-conditioned state movement and fork variance were substantial

Phase 11 determines whether the original effect was:
    static action superiority
    dynamic state realignment
    topology-specific behavior
    or instability/noise
```

Do not erase Phase 8.

Preserve it as the experiment that exposed the phenomenon.

### Phase 11 completion gate

Phase 11 is complete when:

1. Policy comparisons are classified by resulting state comparability.
2. Existing Phase 10 occupancy and transition evidence has been re-analyzed.
3. A minimal set of representative cases has been selected.
4. Any required new runs use independent forks and stable fixtures.
5. Fork-level state movement is inspected rather than only aggregate state.
6. At least one clean same-state comparison is characterized.
7. Strongly state-shifted cases are characterized.
8. Any repeatable skip-induced state movement is classified as beneficial, harmful, or inconclusive.
9. No new decision-tree axis or composite pressure formula is introduced.
10. No contention thresholds are finalized.
11. Productive-handle telemetry remains available.
12. The next phase is derived from the Phase 11 outcome rather than predetermined.

### Required final report

At completion, produce:

```text
Phase 11 Findings: Policy-Conditioned State Dynamics
```

Include:

* authoritative fork-aware throughput results;
* per-fork state-comparability evidence;
* occupancy TV distances;
* contention/body centroid changes;
* `productiveHandleRatio`;
* transition/self-transition differences;
* vector-field observations where useful;
* classification of each targeted comparison as Outcome A/B/C/D;
* whether `SKIP_THEN_STAGED` has any repeatable dynamic effect;
* whether the remaining problem looks static or transition-driven;
* the minimum justified next research question.

Do not propose a broad Phase 12 plan until the Phase 11 result is known.

---

````markdown
## 15. Phase 12 - Investigate Fork-Level Multistability and Contention Staleness

Phase 11 established that the remaining instability is not a repeatable `SKIP_THEN_STAGED` state transition.

Instead, otherwise identical forks can settle into different contention/body attractors under the same policy.

The leading hypotheses are:

1. a genuine scheduler/control-loop bug;
2. stale contention feedback caused by idling suppressing the acquisition attempts required to update the contention estimate.

Phase 12 must distinguish these before any further policy tuning.

Do not refine skip thresholds, productivity thresholds, or contention bands in this phase.

### Core suspected feedback loop

Contention currently measures:

```text
failed lock acquisitions
/
total lock acquisition attempts
````

Idling occurs before execution/acquisition.

If an idle decision is selected, no new acquisition attempt may occur and therefore no new contention observation may be produced.

This can potentially create:

```text
high measured contention
->
idle selected
->
no acquisition attempt
->
no contention update
->
old high contention remains
->
idle selected again
->
high-contention state becomes sticky
```

This is a hypothesis.

Phase 12 must first determine whether this sequence actually occurs.

### Start from a known multistable fixture

Prefer a fixture where Phase 11 showed that the same policy independently entered multiple attractors.

Primary candidate:

```text
E-core
productiveHandleRatio = 0.500
M body
```

Phase 11 showed that both policies independently reached states 7 and 22 in this fixture.

This makes it useful for studying scheduler multistability without attributing the behavior to skip.

Prefer a single fixed execution policy initially.

Use whichever policy reproduces the split most clearly and with the least additional control complexity.

Do not compare multiple policies until the underlying stability behavior is understood.

### Reuse existing evidence first

Before running anything new, inspect the retained per-fork artifacts from Experiments 24 and 25.

For forks that settled into different attractors, compare:

```text
contention history
contention centroid
dominant state
self-transition probability
idle decisions
execution decisions
transition matrices
vector fields
productiveHandleRatio
throughput
```

Determine whether the existing telemetry already shows evidence of contention becoming stale during long idle sequences.

If required information is missing, add only the minimum diagnostic telemetry needed.

### Add contention-staleness diagnostics

Instrument enough information to observe the relationship between idling and contention updates.

Useful per-core diagnostics include:

```text
current contention EWMA
last raw contention observation
cycles since last contention observation
time since last contention observation
consecutive idle decisions
idle duration selected
successful acquisition count
failed acquisition count
total acquisition attempts
execution decisions
local cache availability
productiveHandleRatio
```

Keep this diagnostic instrumentation bounded and outside the production hot path where practical.

Do not redesign the telemetry system.

### Preserve measurement semantics

Do not modify the existing measured contention value during the diagnostic stage.

The existing contention signal represents observed acquisition failures.

No acquisition attempt does not imply that physical contention actually decreased.

Therefore distinguish:

```text
measuredContention:
    existing observed contention EWMA

decisionContention:
    optional aged or confidence-adjusted value used only by the controller
```

Do not silently decay the authoritative measured contention telemetry.

### Reproduce the attractor split

Run a controlled replication of the known multistable fixture.

Keep fixed:

```text
cpuSet
core type
worker count
productive handles
productiveHandleRatio
body workload
source topology
idle policy
execution policy
JVM configuration
randomizeWork = false
```

Use enough independent forks to determine whether both attractors continue to appear.

For each fork record:

```text
throughput
dominant state
occupancy
contention centroid
body centroid
self-transition probability
idle streak distribution
time/cycles between contention updates
```

The first question is:

```text
Do high-contention attractor forks exhibit materially longer contention-staleness and idle streaks than low-contention attractor forks?
```

### Outcome A - Staleness strongly correlates with attractor selection

Evidence would look like:

```text
high-state forks:
    enter high contention
    begin repeated idling
    contention observations become sparse
    contention EWMA remains elevated
    high-state occupancy becomes sticky

low-state forks:
    continue producing contention observations
    contention estimate moves normally
```

If this pattern repeats across forks, proceed to controlled contention-aging experiments.

### Outcome B - Attractor selection occurs without contention staleness

If both attractors continue receiving fresh contention observations at similar rates, the stale-feedback hypothesis is weakened.

Do not tune decay in that case.

Proceed toward investigation of scheduler/control bugs or another missing dynamic variable.

### Test decision-only contention aging

Only if Outcome A is supported, test whether aging stale contention changes the attractor behavior.

Do not alter `measuredContention`.

Derive a temporary decision value from:

```text
measured contention
+
time or cycles since last real contention observation
```

Prefer a time-based formulation because configured idle durations differ.

Conceptual form:

```text
decisionContention =
    measuredContention * decay(staleness)
```

A candidate family may use exponential aging:

```text
decisionContention =
    measuredContention * exp(-dt / tau)
```

where:

```text
dt  = time since last real contention observation
tau = experimental decay constant
```

Do not assume this formula is correct for production.

It is an experimental probe.

### Use a bounded decay sweep

Test only a small set of clearly separated aging strengths.

For example:

```text
baseline:
    no aging

slow aging

medium aging

fast aging
```

Choose actual `tau` values from the observed contention-update and idle timing data.

Do not guess nanosecond values before measuring the natural timescale.

The purpose is not to optimize throughput yet.

The purpose is to determine whether breaking stale positive feedback collapses the multiple attractors.

### Evaluate attractor geometry before peak throughput

For each aging configuration compare:

```text
number of distinct attractors reached
fork-to-fork throughput CV
fork-to-fork occupancy TV
contention centroid spread
dominant-state distribution
dominant self-transition
idle streak distribution
time between contention observations
mean throughput
```

The strongest evidence for stale-feedback causality would be:

```text
baseline:
    forks split across multiple attractors

aging:
    forks converge toward one state population
    contention-update staleness decreases
    fork variance decreases
    throughput remains equal or improves
```

Do not select the fastest decay solely because it has the highest one-run throughput.

### Add a forced-refresh diagnostic

If useful, test a second mechanism that does not continuously decay the signal.

Example diagnostic behavior:

```text
after N consecutive idle decisions
or
after contention has been stale for T time

allow or force one acquisition/execution opportunity
to obtain a fresh contention observation
```

This is a diagnostic intervention, not a proposed production policy.

If periodic refresh destroys the multistability, that is strong evidence that the controller's own idling suppresses the observations required to escape the high-contention state.

### Compare aging and forced refresh

If both mechanisms stabilize the same fixture, determine what they have in common.

The important property may be:

```text
old contention evidence eventually loses authority
```

rather than any particular decay formula.

Do not prematurely encode either mechanism.

### Investigate genuine bugs if staleness is not sufficient

If:

```text
contention remains fresh
or
aging/refresh does not collapse the attractors
```

investigate the scheduler for a genuine state bug.

Focus on differences that could survive across otherwise identical forks:

```text
worker initialization order
handle registration order
cache ownership
worker-rank state
local cache state
batch-size state
contention EWMA initialization
idle-state initialization
source iteration order
lock/acquisition ordering
stale per-worker control state
```

Do not perform broad refactoring.

Use the attractor telemetry to narrow the suspected subsystem first.

### Per-fork analysis remains mandatory

Do not collapse independent forks into aggregate state before interpretation.

For every experiment retain:

```text
one throughput mean per fork
per-fork occupancy
per-fork contention/body centroids
per-fork idle/staleness diagnostics
per-fork transition data
```

A stability mechanism is established only if its effect repeats across independent forks.

### Phase 12 outcome classification

Classify the phase into one of the following outcomes.

#### Outcome A - Stale contention causes multistability

```text
idle suppresses contention observations
stale high contention sustains additional idling
aging or forced refresh collapses the attractor split
```

Next research should focus on the smallest correct freshness mechanism.

#### Outcome B - Staleness contributes but is not sufficient

```text
aging reduces instability
but multiple attractors remain
```

Next research should isolate the remaining state variable or scheduler mechanism.

#### Outcome C - Staleness is not causal

```text
contention remains fresh
or
aging/refresh does not materially affect attractor selection
```

Next research should investigate an implementation/state bug or another dynamic mechanism.

#### Outcome D - No reproducible multistability

```text
the known fixture no longer reliably produces multiple attractors
```

Do not invent a fix.

Document the negative result and identify the next fixture with repeatable fork-level splitting.

### Do not change the production policy yet

During Phase 12 do not finalize:

```text
contention decay
forced refresh
new contention thresholds
productiveHandleRatio thresholds
skip assignments
new decision-tree axes
```

Any aging or refresh behavior is experimental until the causal relationship is demonstrated.

### Phase 12 completion gate

Phase 12 is complete when:

1. A known multistable fixture has been reproduced or ruled out.
2. Per-core contention staleness and idle streaks are observable.
3. High- and low-attractor forks have been compared directly.
4. The correlation between idling, missing contention updates, and attractor selection is known.
5. Decision-only contention aging has been tested if justified by the diagnostic evidence.
6. Forced refresh has been tested if useful as a causal probe.
7. Attractor count and fork variance are compared before and after interventions.
8. Any remaining evidence for a genuine implementation bug is documented.
9. No production decay rule is adopted without causal evidence.
10. The next phase is derived from the result rather than predetermined.

### Required final report

At completion produce:

```text
Phase 12 Findings: Multistability and Contention Staleness
```

Include:

* fixture used to reproduce multistability;
* per-fork attractor assignments;
* per-fork throughput;
* contention-update staleness;
* idle streak behavior;
* contention/body occupancy;
* transition/self-transition behavior;
* effect of decision-only aging;
* effect of forced refresh if tested;
* whether attractor multiplicity changed;
* classification as Outcome A/B/C/D;
* whether the evidence points toward stale feedback, a genuine bug, or another mechanism;
* the minimum justified next research question.

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
