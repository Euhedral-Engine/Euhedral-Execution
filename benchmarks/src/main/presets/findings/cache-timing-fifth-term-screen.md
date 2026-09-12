# CACHE idle timing: controlled fifth-term screen

Historical preparation record. The campaign is complete; see [measured findings](cache-timing-fifth-term-results.md). Its finished preset and linked input files are now preserved under their original paths in `experiments/cache-run-data.tsv`, rather than as runnable old presets.

Prepared only. History does not identify a fifth term. No JMH trials, production changes,
runtime changes, or unsupported 5D fit were performed.

The archive contains 2,454 retained fork/run records and 12,770 nested windows. For each of
four proposed extensions, exact function, runtime, fixture and throughput-only compatibility
checks retain 26 distinct 4D theta and 1,080 live forks (plus 180 matched OFF forks). Every
retained fifth coordinate is zero. These observations establish the four-dimensional surface,
not the effect of a coefficient that never varied. Per-source decisions are in the
[historical audit](../cache-timing/fifth-term-v1/historical_fifth_term_audit.tsv), with full
quantitative checks in each `historical/FAMILY/compatibility.tsv`.

Historical whole-function evidence provides context:

| Added term           | Historical policies    | Exercised coefficient            | Interpretation                                                    |
|----------------------|------------------------|----------------------------------|-------------------------------------------------------------------|
| park body            | live-09/10/20/22/29    | approximately -1.9972 to +1.9972 | Whole-function geometric changes ranged -4.98% to +0.87%.         |
| half-life body       | live-07/08/15/16/30/31 | approximately -1.0397 to +1.0397 | Whole-function changes ranged -5.91% to -1.26%.                   |
| park PHR x body      | live-12                | approximately -1.9972            | Simultaneously changed H interaction; cannot isolate park effect. |
| half-life PHR x body | live-12                | approximately +1.0397            | Simultaneously changed park interaction; cannot isolate H effect. |

The original broad screen was not throughputOnly-compatible with current tuning. Later
live-12 confirmations have compatible throughput-only execution but still change both
interaction terms and other frozen coefficients. Their positive aggregates do not constitute
clean five-dimensional samples. The audit retains coefficient activity, source identities,
CPU/workload coverage and contextual returns without merging incompatible policies into a fit.
None of A-D is ruled out by these coarse, different complete functions. PHR x body is the primary
hypothesis; standalone body offsets distinguish it from a general body effect. A contention
control is omitted to keep the first screen bounded; this does not establish that contention
is unimportant.

## Frozen identifying experiment

The exact sample 7931 function is the development anchor. Its four active values are
`[1.539667508421986, -1.1628579390224267, -0.6064873212242595, 0.21406430608786822]`.
They remain fixed for this identifying slice. Each subsequent family task leaves these four
parameters tunable within their existing bounds and adds exactly one variable:

| Family             | Runtime coefficient path  | Added-coordinate bounds                      |
|--------------------|---------------------------|----------------------------------------------|
| park-body          | `/parkCoefficients/3`     | -0.30010459245033816 .. +0.30010459245033816 |
| half-life-body     | `/halfLifeCoefficients/3` | -0.30010459245033816 .. +0.30010459245033816 |
| park-phr-body      | `/parkCoefficients/6`     | -0.30010459245033816 .. +0.30010459245033816 |
| half-life-phr-body | `/halfLifeCoefficients/6` | -0.30010459245033816 .. +0.30010459245033816 |

The coordinate is a delta around the exact frozen residual. Delta zero reproduces every bit
of the anchor coefficients; it does not erase numerical residuals. Bounds follow
`log(1.35) / max(abs(normalized term))`. Deterministic validation checks the support grid and
all corners of the existing four-parameter box. No bound needed shrinking and no generated
point was rejected. At maximum input magnitude the unclamped effect is +35% or -25.93%.
The actual runtime bounds remain park 15,000..814,375 ns and H 250,000..2,000,000 ns.

The [surface catalog](../cache-timing/fifth-term-v1/surface_catalog.tsv) reports PHR 0,1,2,4
and normalized body -1,0,+1 (log1p (bodyNs) 0,8,16), plus clamp occupancy. These are synthetic
support points, not measured body distributions or a mapping from work units to nanoseconds.

Arms are POLICY_OFF, exact original live-25-center, exact anchor-7931, and negative/positive
levels for each of the four extensions: **11 arms**. Zero levels share the anchor control.
All other coefficients, output transforms and fallback semantics remain unchanged.

Fixtures are scarce S1/W0, S1/W96, S1/W576 at each of R7, R15 and R23, plus plentiful
R7/S7/W0, R7/S7/W96 and R15/S15/W0. The established logical CPU placements are R7=2..15,
R15=2..23 and R23=2..31, resolved to 7/15/23 physical workers, with harness CPU 0.
There are **12 workloads x 11 arms x 2 balanced independent blocks = 264 JVM forks**.
Each uses the existing three 2-second warmups and five 2-second measurements, one million
executions per invocation and the 120-second guard. No wall-clock performance guarantee is implied.
All slow forks and nested windows must remain. No observers are added.

## Analysis and next steps

Use the [runner handoff](../cache-timing/fifth-term-v1/HANDOFF.md) and its benchmark check
before execution. Compare each extension against both OFF and the same-campaign 7931 anchor,
separately by topology and scarce body regime. Report plentiful losses individually. The three
plentiful fixtures are an early damage screen; missing plentiful fixtures and incomplete
aggregates remain unmeasured, so this stage cannot establish production readiness.

After collection, add the new evidence to the existing archive and run the corresponding
`families/FAMILY/tournament.json` through the generic training runner. Each JSON declares five
active paths, exact offsets, parameter bounds, dataset, 48 center/OFF response outputs, grouped
validation, broad offline models, ensembles and reliability-aware proposal search. The original
80 configurations compete alongside their same-evidence four-input ablations (160 configurations
per family). The ablation omits the fifth model input while preserving identical rows and full-theta
folds. A generic variation gate refuses to fit until the added coordinate has three measured
values. There is no claim that every output becomes identifiable from this partial panel.

Judge added complexity by measured improvements over comparable 4D behavior, not merely beating
OFF or reducing training error. If one extension earns a useful body/plentiful tradeoff, advance
its automated 5D tuning. Retain at most two separate families if necessary. If none earns its
complexity, return to 4D. Do not combine new terms or advance to dynamics yet.

Generic tooling changes are parameter offsets for exact zero parity, JSON one-term screen
preparation with behavior-derived bounds, optional model input subsets for same-evidence
ablation, and a required-variation gate. Selecting coefficient paths or dimensions requires JSON,
not a new experiment-specific Python CLI. No candidate weight was chosen manually.

## Old preset cleanup

Removed 214 old CACHE preset files from `src/main/presets/cache-timing`; exact snapshots needed
by regression tests now live under `benchmarks/src/test/resources/cache-timing`. The preceding
13 obsolete preparation files were deleted after consolidation. Every original file is preserved
in the verified archive, with receipts in `experiments/cache-fifth-term-preparation-audit`.
Only `fifth-term-v1` remains in the runnable CACHE preset directory. Test references were updated;
prior findings and measured evidence remain intact. Old task JSONs directly consumed by generic
regression tests remain available. No retained run or measurement window was dropped.

## Validation

The relevant generic Python suite passed 90 tests with two CUDA skips and two deselected legacy
frozen-artifact tests. The focused screen suite then passed 11 tests, including exact Python,
Java config and shared-export parity for all eight extensions plus the anchor across the
300-point support grid and invalid-input fallbacks. Four focused CACHE harness Java tests passed
through repository Mise Gradle after relocating their resources. Deterministic regeneration
reproduced the candidate manifest exactly. Root artifact hashes, source identity, physical
topology, the one-new-coefficient invariant and `git diff --check` passed. No JMH ran.
