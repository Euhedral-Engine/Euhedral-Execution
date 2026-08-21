## 17. Phase 14 - Reopen the High-Contention Execution and Idle Surface

Phase 12 and the Phase 13 mechanism investigations did not identify a simple implementation defect responsible for the rare state-22 attractor.

The attractor:

```text
- uses fresh contention observations;
- survives large changes in pull size and lock hold duration;
- survives collision-only handle shuffling;
- survives full worker-local handle shuffling;
- remains productive at roughly 11.4 million executions/s;
- occurs rarely under the tested high-contention fixture.
```

The current evidence is compatible with a worker-phase or staging/draining limit cycle, but further mechanism-specific investigation is not justified yet.

More importantly, the pathological state has been observed while the high-contention policy space is artificially constrained.

Earlier upper-contention calibration primarily compared:

```text
STAGED
SKIP_THEN_STAGED
```

with an already-selected idle policy.

The upper contention bands have not been systematically searched using:

```text
DIRECT
STAGED

combined with

selective follower idle
```

Phase 14 therefore returns to policy calibration.

The objective is not to eliminate state 22 directly.

The objective is:

```text
maximize high-contention throughput
+
identify stable execution/idle combinations
+
observe whether better policies naturally avoid the rare attractor
```

State 22 remains diagnostic evidence, not an optimization target.

### Core hypothesis

The current forced-STAGED regime may permit a stable producer/consumer phase relationship:

```text
some workers stage work
->
other workers drain it
->
roles or phases alternate
->
acquisition contention remains very high
->
the system settles into a slower but productive limit cycle
```

This is only a hypothesis.

Selective `DIRECT` execution and follower idling may change that operating geometry:

```text
selective idle
    -> removes workers from the active contention pool

DIRECT
    -> changes how work is acquired and consumed

STAGED
    -> preserves decoupled production and draining
```

A useful high-contention policy may therefore require a combination rather than one globally forced execution action.

### Stop the mechanism search

Do not add new diagnostics for:

```text
handle sequence timing
lock handoff phase
worker oscillator phase
staging/draining role transitions
```

unless Phase 14 shows that the remaining multistability materially blocks policy calibration.

Do not attempt to design a special state-22 escape rule.

Do not add another decision-tree axis.

### Freeze unrelated mechanics

Use the current implementation as fixed during Phase 14.

Do not tune:

```text
pull bucket target
FLOOR/CEIL bucketing
handle shuffling
contention smoother
body estimator
skip semantics
productiveHandleRatio
```

Use the current production pull-bucketing baseline:

```text
FLOOR / 2048
```

Do not include the Phase 13 pull-bucketing matrix in this phase.

The pull-bucketing investigation is closed unless later evidence directly reopens it.

### Search only the high-contention region

Do not rerun the complete low-contention policy surface.

Existing evidence already supports `DIRECT` through the low and middle contention region.

Phase 14 begins where the policy is unresolved:

```text
approximately 65% through 100% acquisition contention
```

Use the existing high-contention landmarks and fixtures as starting anchors.

Do not assume the current high-contention band boundaries are optimal.

Do not tune those boundaries yet.

First identify action economics within the existing regions.

### Primary anchor fixture

Begin with the known multistable fixture:

```text
8 E-core workers
4 productive parallel sources
productiveHandleRatio = 0.500
M body
deterministic work
current decision thresholds
current batch cap
current handle behavior
```

This fixture is useful because:

```text
- it naturally occupies the upper contention region;
- it has repeatedly exhibited state 22;
- it has extensive retained baseline evidence;
- it can reveal both throughput and stability effects.
```

Use it as the first search surface.

Do not treat it as sufficient evidence for a portable final policy.

### First coarse execution/idle matrix

Search execution action and follower idle jointly.

Execution actions:

```text
DIRECT
STAGED
```

Follower idle candidates:

```text
0 ns
1,000 ns
5,000 ns
15,000 ns
```

This creates the coarse treatment set:

```text
DIRECT / 0 ns
DIRECT / 1 us
DIRECT / 5 us
DIRECT / 15 us

STAGED / 0 ns
STAGED / 1 us
STAGED / 5 us
STAGED / 15 us
```

The existing high-contention STAGED policy with its current idle duration must be represented explicitly as a baseline treatment.

Do not include skip in the first coarse matrix.

`SKIP_THEN_STAGED` has already received substantial upper-region investigation and has not demonstrated a stable enough advantage to justify expanding this first search.

Skip may be reintroduced later only if the DIRECT/STAGED plus idle surface leaves a specific unresolved region.

### Preserve leader/follower semantics

Do not change idle eligibility semantics.

Rank zero remains active according to the existing policy.

Follower idle remains the mechanism for reducing the active contention population.

This phase calibrates:

```text
when followers participate
+
how participating workers execute
```

It does not introduce worker election or explicit role assignment.

### Use treatment switching efficiently

If the harness can safely switch execution and idle treatments between measurement iterations, use the same balanced within-fork approach developed for Phase 13.

Every treatment must occur:

```text
once per independent fork
and
at balanced measurement positions across forks
```

Use a Williams or equivalent counterbalanced order.

Record:

```text
fork
measurement position
execution action
idle duration
```

Reset all policy/controller state required to prevent the previous treatment from directly determining the next treatment.

Do not reset unrelated JVM/JIT state.

### Independent forks remain authoritative

Repeated treatments inside one JVM are not independent replication.

For each treatment retain the response from every independent fork.

Analyze separately:

```text
within-fork treatment response
across-fork treatment response
```

Do not claim a winner from pooled measurement iterations alone.

### Primary measurements

For every execution/idle treatment retain:

```text
throughput

fork CV
within-fork CV

contention centroid
contention occupancy

body centroid
body occupancy

dominant state
dominant-state probability
dominant self-transition

acquisition attempts
successful acquisitions
failed acquisitions
successful acquisition share

idle fraction
idle decisions

productiveHandleRatio
```

Retain existing ownership and pull telemetry only if it already exists cheaply.

Do not add new hot-path diagnostics merely for this phase.

### State-22 tracking

For every measurement classify whether state 22 becomes dominant.

Retain:

```text
state-22 occurrence count
forks containing state 22
state-22 occupancy
state-22 throughput
state-22 acquisition success
state-22 self-transition
```

But do not rank treatments primarily by state-22 frequency.

The ordering remains:

```text
1. throughput
2. repeatability across forks
3. absence of severe regressions
4. acquisition/contention behavior
5. state-22 frequency
```

A faster stable policy is preferable even if it occasionally enters state 22.

A slower policy is not preferable merely because state 22 disappears.

### Evaluate execution/idle interaction

The important question is not:

```text
Is DIRECT better than STAGED?
```

or:

```text
Is idling useful?
```

independently.

The important question is:

```text
Does the best execution action depend on follower participation?
```

For example:

```text
DIRECT / 0 ns
may lose

DIRECT / 5 us
may win
```

while:

```text
STAGED / 0 ns
may lose

STAGED / 5 us
may win
```

Treat action x idle duration as an interaction surface.

For each idle duration calculate:

```text
DIRECT versus STAGED throughput delta
```

For each execution action calculate:

```text
idle-duration throughput response
```

Use the existing comparison framework rather than manually comparing pooled means.

### Look for active-pool effects

Because idle removes followers from contention, observe whether candidate treatments change:

```text
acquisition success
contention centroid
idle fraction
throughput
```

together.

Useful evidence would look like:

```text
more selective participation
->
higher acquisition success
->
lower pathological contention
->
equal or higher throughput
```

But do not require contention to decrease.

A treatment may tolerate higher contention while producing more throughput.

### First coarse outcome

After the anchor matrix, classify the local surface.

#### Case A - Clear DIRECT plus idle region

Example:

```text
DIRECT / short or medium idle
consistently beats STAGED
```

Then the previous all-STAGED assumption is invalid for this high-contention region.

Proceed to local refinement around the winning idle interval.

#### Case B - Clear STAGED plus idle region

If STAGED remains clearly superior but idle duration materially changes throughput, refine idle only.

Do not continue searching execution action where the winner is already clear.

#### Case C - Execution winner depends on idle duration

This is strong evidence of coupled execution/participation control.

Refine around the crossover.

Do not collapse execution and idle into independent calibrations.

#### Case D - Broad plateau

If several treatments are within approximately noise while producing similar stability, prefer the simpler and more robust region.

Do not tune toward a fragile single-point maximum.

### Body sensitivity check

Once the M-body anchor is understood, test neighboring body regimes only where required.

At minimum consider:

```text
S body
M body
H body
```

Do not automatically rerun all five body bands.

The purpose is to answer:

```text
Does the high-contention execution/idle winner change materially with body cost?
```

If S/M/H produce the same action relationship, treat that as evidence for a lower-dimensional upper policy.

If the action changes with body cost, preserve the body split and refine only the affected rows.

Add XS or XH body only if the neighboring-body evidence indicates that an endpoint is required.

### Contention sensitivity check

After identifying useful execution/idle combinations, move across existing high-contention source topologies.

Prefer retained fixtures that naturally occupy approximately:

```text
65-80%
80-90%
90-97%
97-100%
```

Do not tune contention thresholds yet.

First determine whether the winning action/idle combination actually changes across those physical contention regimes.

This is a policy-surface search, not a threshold search.

### P-core and E-core separation

Do not assume the E-core anchor transfers directly to P cores.

Use the E-core fixture for discovery because it reproduces the instability.

After finding a candidate high-contention policy, verify representative P-core fixtures.

Do not merge P-core and E-core evidence if they disagree.

A topology-specific difference is acceptable evidence.

Do not add core type as a production decision-tree axis during this phase.

### Use state 22 as explanatory telemetry

If better policies also eliminate or sharply reduce state 22:

```text
higher throughput
+
lower state-22 frequency
```

interpret that as evidence that the attractor was partly a consequence of the constrained all-STAGED policy.

Do not claim that the underlying phase mechanism has been fully identified.

If state 22 persists but the new policy is faster overall, continue calibration.

If state 22 becomes common enough to dominate candidate variance or prevent policy identification, only then reopen the mechanism investigation.

### Reintroduce skip only if justified

Do not include `SKIP_THEN_DIRECT` or `SKIP_THEN_STAGED` in the initial coarse matrix.

After DIRECT/STAGED plus idle calibration:

```text
if one narrow region remains unstable
or
if an execution crossover repeatedly fails to settle
```

then compare the best stable action against its corresponding skip variant.

Examples:

```text
DIRECT
vs
SKIP_THEN_DIRECT

STAGED
vs
SKIP_THEN_STAGED
```

Do not reopen a broad four-action surface.

### Local idle refinement

If a useful idle region is found, refine only around that region.

Example:

```text
1 us
5 us
15 us
```

reveals a peak near 5 us.

Then test something like:

```text
2 us
3 us
5 us
7 us
10 us
```

The exact refinement values must come from the coarse response shape.

Do not predeclare a dense global idle sweep.

Prefer a stable plateau over a narrow point maximum.

### Threshold tuning comes after action discovery

Do not change:

```text
65%
80%
90%
97%
```

or whatever current candidate upper thresholds are in use until the action/idle relationships are understood.

Once neighboring physical contention fixtures show different preferred policies, then the next phase may place boundaries around those crossovers.

Thresholds encode discovered behavior.

They must not be used to manufacture it.

### Possible outcomes

#### Outcome A - Selective DIRECT/idling resolves the upper region

Evidence:

```text
one or more DIRECT + idle combinations
repeatedly outperform forced STAGED
and
produce stable fork behavior
```

State 22 may also decrease or disappear.

Next phase:

```text
refine the DIRECT/STAGED upper crossover
and encode the smallest supported policy surface
```

#### Outcome B - STAGED remains correct but idle was under-calibrated

Evidence:

```text
STAGED remains the execution winner
but follower idle duration materially changes throughput/stability
```

Next phase:

```text
refine high-contention idle participation
without changing execution selection
```

#### Outcome C - Execution and idle are strongly coupled

Evidence:

```text
DIRECT/STAGED winner reverses as idle duration changes
```

Next phase:

```text
map the minimum joint execution/idle regions
before tuning contention boundaries
```

Do not independently optimize execution and idle.

#### Outcome D - Existing policy remains near-optimal

Evidence:

```text
the current STAGED + idle baseline remains on the stable throughput plateau
and alternatives provide no repeatable material benefit
```

Then stop upper-region calibration.

Treat state 22 as a rare known multistable regime unless later production workloads make it material.

#### Outcome E - Body or topology changes the winner

Evidence:

```text
different body rows
or
P/E fixtures
require different execution/idle combinations
```

Preserve the discovered split.

Do not average contradictory regimes into one policy.

### Do not optimize against the synthetic attractor

The high-contention fixture is deliberately stressful.

The final goal is not:

```text
state22Count == 0
```

The goal is:

```text
high throughput
+
stable behavior
+
no severe regression
+
a policy surface supported by physical evidence
```

If state 22 remains rare and bounded under the best-performing policy, document it and continue.

### Phase 14 completion gate

Phase 14 is complete when:

1. The known M-body high-contention fixture has been tested across DIRECT/STAGED x coarse idle duration.
2. Treatment order has been balanced across independent forks.
3. Throughput and variance are retained per fork.
4. Acquisition success and contention occupancy are retained.
5. State-22 frequency is retained as diagnostic evidence.
6. The execution/idle interaction is classified.
7. Any useful idle interval has been locally refined.
8. Neighboring body rows have been tested only as required.
9. Representative high-contention source topologies have been checked only after the anchor surface is understood.
10. P-core verification has been performed for any candidate intended to be portable.
11. Skip has not been reintroduced unless a specific unresolved region justifies it.
12. Contention thresholds have not been tuned before action crossovers are established.
13. No special state-22 escape policy has been added.
14. The result is classified as Outcome A/B/C/D/E.

### Required final report

At completion produce:

```text
Phase 14 Findings: High-Contention Execution and Idle Surface
```

Include:

* exact anchor fixture;
* execution/idle treatment matrix;
* balanced treatment order;
* fork-level throughput;
* within-fork and across-fork CV;
* contention occupancy and centroid;
* acquisition success;
* idle participation;
* dominant states and self-transition;
* state-22 occurrence by treatment;
* DIRECT versus STAGED response at each idle duration;
* idle response under each execution action;
* any local refinement;
* body sensitivity evidence;
* contention/topology sensitivity evidence;
* P-core versus E-core evidence if tested;
* whether the all-STAGED upper-region assumption survives;
* Outcome A/B/C/D/E classification;
* the minimum justified next calibration question.
