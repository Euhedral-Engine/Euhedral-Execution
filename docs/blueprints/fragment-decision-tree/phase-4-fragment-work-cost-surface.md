# Phase 4: Corrected Fragment Work-Cost Surface

Status: completed 2026-08-11; no production policy implemented

Plan: [`phase-4-fragment-work-cost-surface.md`](../../plans/phase-4-fragment-work-cost-surface.md)

## Decision under investigation

The failed-handle acquisition defect is closed. Pre-fix plentiful DIRECT measurements that depended
on permanent worker starvation are invalid scheduler evidence and are not inputs to this phase.

The current smallest experimental tree is:

```text
work cost
    |
    +-- cheap     -> DIRECT candidate
    |
    +-- expensive -> unresolved
```

This phase tests whether the unresolved leaf is explained by work cost alone or must split on the
physical availability of independent upstream work relative to two execution workers. `PLENTIFUL`
and `SCARCE` remain fixture names, not proposed runtime features.

## Hypotheses

- H1: With two independently available sources and corrected participation, DIRECT wins for cheap
  work because its lower intrinsic path overhead dominates.
- H2: Increasing work cost may produce a stable region where STAGED wins because request-first/cache
  behavior improves utilization enough to amortize its overhead.
- H3: One genuinely shared source changes the location or shape of any winner transition.
- H4: If H3 holds, the supported decision surface needs work cost plus a measurable representation
  of independent work availability relative to active workers.
- H5: Existing owner-local execution latency is a usable work-cost signal: its measurement-only
  estimate is stable within a row and tracks isolated synthetic body cost monotonically well enough
  to distinguish any observed winner regions.

A crossover is not required. A path may win throughout one source shape, or STAGED may win only in a
bounded region.

## Fixture extension

Extend `FragmentPathCalibrationBenchmark`; do not add another graph implementation.

Add two JMH methods:

- `workCostOnly`: the scheduler-free arithmetic loop parameterized by `workRounds`;
- `workCostDecision`: the existing two-worker forced-path graph using the same `workRounds`.

Use initial rounds `0, 8, 24, 48, 96, 176, 256, 512`. The 256-round body is the established
approximately 225 ns anchor. The other values target the requested coarse logarithmic curve while
avoiding timer-based busy work. The scheduled executor must retain its evolving result in
worker-owned state; the isolated JMH method must return the result.

Keep the existing fixed `cpuWorkOnly` and `cpuWorkDecision` behavior at 256 rounds so Phase 3
commands remain reproducible. The new state alone owns `workRounds`.

If the drill-down retains intermediate points, add them to the new states' default parameter list
after measurement so the completed small surface is directly reproducible.

`workCostDecision` varies exactly:

- forced mode: `DIRECT`, `STAGED`;
- source shape: `PLENTIFUL`, `SCARCE`;
- work rounds: the eight initial predeclared values, plus only retained reversal drill-down points.

Everything else remains fixed:

- two same-kind workers on one socket;
- the established selected logical CPUs and natural source publication;
- one source for `SCARCE`, one source per worker for `PLENTIFUL`;
- unordered routing and the existing repeating frame pools;
- batch target 32 and 1,048,576-frame completion windows;
- three 3-second warmups and five 5-second measurements;
- three forks for retained rows.

Do not rerun Phase 3 acquisition-layout perturbations. Existing handle counters may remain as a
corrected-reachability diagnostic, but they are not a candidate decision feature.

## Existing runtime-signal validation

The normal fragment already times execution operations around local cache, remote cache, and direct
upstream drains. Each timing covers one or more completed frames, and Core divides elapsed
nanoseconds by that frame count before smoothing and publishing execution latency at completed batch
boundaries.

For the sweep only, construct the existing `BaseCloneableObject` with a trial-local
`SimpleMeterRegistry` and an otherwise-default `FragmentConfig`. Do not modify Core. At every JMH
iteration boundary snapshot each worker/core latency summary's count and total. Retain
measurement-only deltas and derive:

```text
reported service estimate = delta total latency / delta report count
```

Report the per-worker and aggregate estimates for every fork. This is an observation of the existing
batch-compatible statistic, not per-frame benchmark timing. Note that enabling the normal metrics
sink adds fixed batch-boundary reporting work; because it is held constant across all sweep rows,
the surface remains comparative, but results are not direct replacements for metrics-disabled Phase
3 throughput.

H5 passes if the estimate is finite, stable enough not to overlap materially different winner
regions, and monotonically represents increasing isolated body cost. It need not equal isolated body
ns/op because it includes the execution boundary and scheduler data-path cost. If it folds different
body-cost regions together or is dominated by path-specific overhead, reject it and name
completed-batch elapsed time divided by completed frames as the next bounded signal experiment.

## Measurement and interpretation

Measure `workCostOnly` first and record mean, JMH error, and fork-level results. Then run the
complete coarse scheduled surface. For each source-shape/work-cost pair compute:

```text
advantage = (STAGED frames/s - DIRECT frames/s) / DIRECT frames/s
```

Positive values favor STAGED. Retain each mode's aggregate JMH result and each fork's throughput,
worker completion fractions, dominance, effective lanes, and existing service estimate. Derive
effective lanes from the matching isolated body plus scheduling floor only where the estimate is
physically meaningful; otherwise report participation and mark `L` not comparable rather than
inventing a ceiling.

Do not interpolate a precise nanosecond threshold from the coarse points. Classify contiguous winner
regions. If adjacent points reverse the winner:

1. confirm all three forks agree on the winner and remain free of discrete participation regimes;
2. add at most two deterministic round counts inside that interval;
3. test both source shapes at those same counts;
4. state a rough transition band bounded by measured body costs.

Do not vary batch size in this phase. Batch size becomes the next branch only if the fixed-32
transition is unstable or the same work/source row shows unexplained winner or participation
changes.

## Anomaly and defect rule

Inspect before interpreting when a row has discrete fork clusters, dominance materially above the
corrected two-worker baseline, non-monotonic isolated body cost, unexpectedly non-monotonic path
throughput, or an advantage interval that spans zero widely.

If a new correctness defect is suspected:

1. isolate the smallest mechanism;
2. prove that it causes the surprising row;
3. correct it narrowly only if proven;
4. invalidate and rerun affected evidence;
5. resume from corrected behavior.

Do not encode defects or unexplained variance as a decision branch.

## Stop conditions

Stop broad expansion and return to design if:

- existing metrics alter worker participation or path ordering enough to invalidate the surface;
- the work loop does not produce a monotonic isolated-cost sequence;
- either source shape develops unexplained discrete fork regimes;
- multiple adjacent reversals cannot be localized with two intermediate points;
- source availability and work cost appear entangled with another uncontrolled fixture variable;
- validation would require per-frame timing, production instrumentation, locks, or shared writes.

## Acceptance and next leaf

The completion record must contain:

- environment and exact JMH commands;
- isolated actual ns/op for every retained round count;
- corrected DIRECT and STAGED curves for both source shapes;
- raw fork throughput and participation, JMH error, and relative advantage;
- H1-H5 verdicts and any falsified threshold shape;
- the runtime-signal verdict;
- the smallest supported decision tree; and
- exactly one unresolved branch.

No production policy is implemented. If the source shapes have materially different winner regions,
the default next leaf is the measurable source-availability quantity beneath those fixtures. If they
do not, the next leaf is batch-size stability at the observed work-cost transition. A surprising
physical mechanism may replace either only when the retained evidence requires it.

## Verification

Before handoff run:

-
`mise exec -- gradle :benchmarks:test --tests io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmarkTest`
- `mise exec -- gradle :benchmarks:assemble`
- the declared isolated and scheduled JMH runs;
- `mise exec -- gradle :benchmarks:test :benchmarks:assemble`
- `mise exec -- gradle build`
- `git diff --check`
- `git status --short`

Append completion notes here; do not create a separate audit document.

## Completion notes

Implemented and measured on 2026-08-11. Production Core was not changed. The benchmark extension
adds a parameterized form of the existing arithmetic body, two JMH entry points, measurement-only
worker participation reports, and observation of Core's existing execution-latency metrics. The 64-
and 80-round drill-down points were retained in the default small sweep after they localized the
scarce transition.

Changed files:

-
`benchmarks/src/main/java/io/euhedral_execution/core/control_plane/FragmentPathCalibrationBenchmark.java`
-
`benchmarks/src/test/java/io/euhedral_execution/core/control_plane/FragmentPathCalibrationBenchmarkTest.java`
- `docs/plans/phase-4-fragment-work-cost-surface.md`
- this blueprint

### Environment and protocol

- CPU: Intel Core i9-14900K, x86-64, one socket, 24 physical cores, 32 logical CPUs
- Effective CPUs: 0-31; workers: logical CPUs 0 and 6 on physical cores 0 and 1
- Cache: 36 MiB shared L3
- JVM: OpenJDK 64-Bit Server VM 21.0.2+13-58
- JMH: 1.37, three forks, three 3-second warmups, five 5-second measurements
- Batch target: 32; completion window: 1,048,576 frames
- Source layouts: two natural handles for `PLENTIFUL`; one natural shared handle for `SCARCE`
- Modes: forced `DIRECT` and forced `STAGED`

After `mise exec -- gradle :benchmarks:assemble`, the retained runs used:

```text
mise exec -- java -XX:+UseThreadPriorities --enable-native-access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.platform=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dlogback.configurationFile=benchmark-logback.xml \
  -cp 'benchmarks/build/euhedral-benchmark.jar:benchmarks/build/lib/*' \
  org.openjdk.jmh.Main \
  'io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmark.<method>' \
  -p workRounds=<rounds> \
  [-p mode=DIRECT,STAGED -p sourceShape=PLENTIFUL,SCARCE -p handleLayout=NATURAL] \
  -f 3 -wi 3 -w 3s -i 5 -r 5s -foe true \
  -jvmArgsAppend '<the same JVM and logging flags above>'
```

The initial isolated and scheduled round sets were `0,8,24,48,96,176,256,512`. After the scarce
winner became ambiguous at 48 rounds and STAGED won at 96 rounds, the bounded follow-up used only
`64,80` for the isolated body and both mode/source combinations. Raw JMH JSON and logs were retained
during the pass as `/tmp/phase4-work-only*` and `/tmp/phase4-work-surface*`; all fork-level evidence
needed for review is reproduced below.

### Isolated body calibration

JMH error is the reported 99.9% error. The body curve is monotonic, tightly separated, and
reproduces the fixed 256-round approximately 225 ns anchor.

| Rounds | Mean ns/op | Error ns |
|-------:|-----------:|---------:|
|      0 |      0.353 |    0.009 |
|      8 |      7.666 |    0.010 |
|     24 |     21.566 |    0.039 |
|     48 |     42.563 |    0.101 |
|     64 |     56.533 |    0.210 |
|     80 |     70.689 |    0.104 |
|     96 |     84.657 |    0.155 |
|    176 |    154.621 |    0.480 |
|    256 |    225.235 |    0.286 |
|    512 |    449.914 |    0.589 |

### Corrected path curves

Throughput and error are frames/s. Advantage is `(STAGED - DIRECT) / DIRECT`; positive values favor
STAGED. An error overlap means the independent JMH 99.9% intervals overlap and no stable winner is
claimed at that point.

| Shape     | Body ns |      DIRECT |     Error |     STAGED |     Error | Advantage | Winner  |
|-----------|--------:|------------:|----------:|-----------:|----------:|----------:|---------|
| PLENTIFUL |   0.353 | 118,663,247 |   950,982 | 35,168,152 |   312,118 |   -70.36% | DIRECT  |
| PLENTIFUL |   7.666 |  99,818,132 | 3,125,304 | 34,483,564 |   276,635 |   -65.45% | DIRECT  |
| PLENTIFUL |  21.566 |  58,668,256 |   704,988 | 29,421,321 |   639,629 |   -49.85% | DIRECT  |
| PLENTIFUL |  42.563 |  35,666,122 |   149,255 | 22,293,118 |   136,077 |   -37.49% | DIRECT  |
| PLENTIFUL |  56.533 |  28,515,574 |   158,968 | 19,665,117 |   156,399 |   -31.04% | DIRECT  |
| PLENTIFUL |  70.689 |  23,790,026 |   178,584 | 17,061,503 |   163,333 |   -28.28% | DIRECT  |
| PLENTIFUL |  84.657 |  20,249,648 |   100,775 | 15,242,097 |    80,639 |   -24.73% | DIRECT  |
| PLENTIFUL | 154.621 |  11,821,413 |    71,660 |  9,890,967 |    42,857 |   -16.33% | DIRECT  |
| PLENTIFUL | 225.235 |   8,319,949 |    14,972 |  7,337,289 |    48,402 |   -11.81% | DIRECT  |
| PLENTIFUL | 449.914 |   4,279,883 |    12,626 |  3,988,723 |    20,639 |    -6.80% | DIRECT  |
| SCARCE    |   0.353 |  47,957,792 | 1,593,260 | 46,884,124 | 1,938,726 |    -2.24% | overlap |
| SCARCE    |   7.666 |  47,316,500 | 1,250,576 | 42,884,260 | 2,157,178 |    -9.37% | overlap |
| SCARCE    |  21.566 |  36,154,761 |    89,566 | 32,202,066 |   197,069 |   -10.93% | DIRECT  |
| SCARCE    |  42.563 |  24,457,943 |   362,944 | 24,732,290 |   639,152 |    +1.12% | overlap |
| SCARCE    |  56.533 |  20,526,667 |   357,004 | 20,723,077 |   253,456 |    +0.96% | overlap |
| SCARCE    |  70.689 |  17,630,054 |   583,538 | 18,199,830 |   296,933 |    +3.23% | overlap |
| SCARCE    |  84.657 |  15,401,594 |   541,632 | 16,297,204 |    38,612 |    +5.82% | STAGED  |
| SCARCE    | 154.621 |   9,667,600 |    30,715 | 10,223,469 |    87,121 |    +5.75% | STAGED  |
| SCARCE    | 225.235 |   6,974,918 |    34,415 |  7,567,563 |    22,481 |    +8.50% | STAGED  |
| SCARCE    | 449.914 |   2,708,084 |    19,482 |  4,067,987 |    25,192 |   +50.22% | STAGED  |

The plentiful curve has no winner reversal through 449.914 ns. DIRECT's advantage decreases smoothly
from 70.36% to 6.80%, but remains outside the error bands at every point. Scarcity changes the
shape: DIRECT is the last clear winner at 21.566 ns; 42.563, 56.533, and 70.689 ns form a broad
transition zone; STAGED is the first clear winner at 84.657 ns and remains ahead through 449.914 ns.
The increasing 50.22% advantage at the largest scarce point falsifies a simple model in which the
paths only converge asymptotically under all source conditions.

### Raw fork-level evidence

Fork means are means of their five JMH measurements. Fractions and dominance aggregate the five
matching completion windows. The final column is the matching measurement-only mean of Core's
existing execution-latency summary. Every row retained both productive workers. The largest
dominance, 0.5499 in cheap DIRECT/scarce, is continuous imbalance under one shared handle, not the
pre-fix `D = 1.0` lost-worker regime.

| Shape     | Body ns | Mode   | Fork means frames/s               | Fork f0/f1                                    | Fork D                   | Existing signal ns       |
|-----------|--------:|--------|-----------------------------------|-----------------------------------------------|--------------------------|--------------------------|
| PLENTIFUL |   0.353 | DIRECT | `[118658005,119185140,118146595]` | `[0.4923/0.5077,0.4992/0.5008,0.4946/0.5054]` | `[0.5077,0.5008,0.5054]` | `[8.13,8.15,8.19]`       |
| PLENTIFUL |   0.353 | STAGED | `[35114570,35150671,35239215]`    | `[0.5065/0.4935,0.5006/0.4994,0.5000/0.5000]` | `[0.5065,0.5006,0.5000]` | `[18.42,18.08,18.26]`    |
| PLENTIFUL |   7.666 | DIRECT | `[101655011,97046063,100753322]`  | `[0.5049/0.4951,0.5092/0.4908,0.4982/0.5018]` | `[0.5049,0.5092,0.5018]` | `[10.90,11.77,11.12]`    |
| PLENTIFUL |   7.666 | STAGED | `[34312165,34541861,34596665]`    | `[0.5012/0.4988,0.5008/0.4992,0.4994/0.5006]` | `[0.5012,0.5008,0.5006]` | `[19.49,19.14,19.35]`    |
| PLENTIFUL |  21.566 | DIRECT | `[58524007,57971043,59509718]`    | `[0.5005/0.4995,0.5039/0.4961,0.5014/0.4986]` | `[0.5005,0.5039,0.5014]` | `[25.85,26.03,25.24]`    |
| PLENTIFUL |  21.566 | STAGED | `[28732280,30132400,29399284]`    | `[0.4998/0.5002,0.4998/0.5002,0.4994/0.5006]` | `[0.5002,0.5002,0.5006]` | `[31.84,30.34,31.01]`    |
| PLENTIFUL |  42.563 | DIRECT | `[35542107,35849512,35606747]`    | `[0.4991/0.5009,0.4995/0.5005,0.4991/0.5009]` | `[0.5009,0.5005,0.5009]` | `[47.56,47.02,47.51]`    |
| PLENTIFUL |  42.563 | STAGED | `[22378541,22379101,22121713]`    | `[0.4998/0.5002,0.4998/0.5002,0.4999/0.5001]` | `[0.5002,0.5002,0.5001]` | `[52.18,51.58,51.94]`    |
| PLENTIFUL |  56.533 | DIRECT | `[28504279,28594259,28448183]`    | `[0.4994/0.5006,0.4977/0.5023,0.4970/0.5030]` | `[0.5006,0.5023,0.5030]` | `[61.29,61.22,61.37]`    |
| PLENTIFUL |  56.533 | STAGED | `[19557870,19623656,19813824]`    | `[0.4998/0.5002,0.5014/0.4986,0.5001/0.4999]` | `[0.5002,0.5014,0.5001]` | `[64.92,66.25,65.24]`    |
| PLENTIFUL |  70.689 | DIRECT | `[23783070,23989769,23597238]`    | `[0.4997/0.5003,0.4998/0.5002,0.5000/0.5000]` | `[0.5003,0.5002,0.5000]` | `[75.33,74.78,75.97]`    |
| PLENTIFUL |  70.689 | STAGED | `[17184884,16874751,17124874]`    | `[0.4998/0.5002,0.4998/0.5002,0.4999/0.5001]` | `[0.5002,0.5002,0.5001]` | `[79.21,79.82,80.34]`    |
| PLENTIFUL |  84.657 | DIRECT | `[20164178,20249807,20334958]`    | `[0.5004/0.4996,0.4993/0.5007,0.4997/0.5003]` | `[0.5004,0.5007,0.5003]` | `[90.40,90.05,89.54]`    |
| PLENTIFUL |  84.657 | STAGED | `[15299700,15150061,15276530]`    | `[0.4999/0.5001,0.4999/0.5001,0.4999/0.5001]` | `[0.5001,0.5001,0.5001]` | `[93.87,94.30,94.07]`    |
| PLENTIFUL | 154.621 | DIRECT | `[11733698,11843485,11887055]`    | `[0.4990/0.5010,0.5005/0.4995,0.4996/0.5004]` | `[0.5010,0.5005,0.5004]` | `[161.88,159.91,159.49]` |
| PLENTIFUL | 154.621 | STAGED | `[9843212,9895474,9934216]`       | `[0.5000/0.5000,0.5000/0.5000,0.4998/0.5002]` | `[0.5000,0.5000,0.5002]` | `[163.82,164.09,165.10]` |
| PLENTIFUL | 225.235 | DIRECT | `[8333659,8301792,8324396]`       | `[0.4979/0.5021,0.4995/0.5005,0.5000/0.5000]` | `[0.5021,0.5005,0.5000]` | `[231.28,232.10,231.32]` |
| PLENTIFUL | 225.235 | STAGED | `[7393502,7314187,7304177]`       | `[0.5002/0.4998,0.4998/0.5002,0.4999/0.5001]` | `[0.5002,0.5002,0.5001]` | `[235.19,235.59,235.47]` |
| PLENTIFUL | 449.914 | DIRECT | `[4286999,4278715,4273936]`       | `[0.4989/0.5011,0.4999/0.5001,0.4983/0.5017]` | `[0.5011,0.5001,0.5017]` | `[457.65,458.55,459.41]` |
| PLENTIFUL | 449.914 | STAGED | `[3969391,3985419,4011360]`       | `[0.4982/0.5018,0.5000/0.5000,0.4999/0.5001]` | `[0.5018,0.5000,0.5001]` | `[465.54,464.25,463.03]` |
| SCARCE    |   0.353 | DIRECT | `[48394957,46020513,49457906]`    | `[0.5160/0.4840,0.5207/0.4793,0.5151/0.4849]` | `[0.5160,0.5207,0.5151]` | `[22.82,23.78,22.13]`    |
| SCARCE    |   0.353 | STAGED | `[48360521,47865449,44426403]`    | `[0.5110/0.4890,0.5043/0.4957,0.5391/0.4609]` | `[0.5110,0.5043,0.5391]` | `[15.40,17.15,17.03]`    |
| SCARCE    |   7.666 | DIRECT | `[45962186,47736377,48250936]`    | `[0.4939/0.5061,0.5499/0.4501,0.5015/0.4985]` | `[0.5061,0.5499,0.5015]` | `[23.31,21.91,21.58]`    |
| SCARCE    |   7.666 | STAGED | `[44944298,40289684,43418798]`    | `[0.5101/0.4899,0.5046/0.4954,0.4985/0.5015]` | `[0.5101,0.5046,0.5015]` | `[18.68,22.17,18.81]`    |
| SCARCE    |  21.566 | DIRECT | `[36120932,36255530,36087821]`    | `[0.5045/0.4955,0.4898/0.5102,0.5134/0.4866]` | `[0.5045,0.5102,0.5134]` | `[34.85,35.06,34.66]`    |
| SCARCE    |  21.566 | STAGED | `[32026746,32344527,32234925]`    | `[0.5082/0.4918,0.4956/0.5044,0.5179/0.4821]` | `[0.5082,0.5044,0.5179]` | `[34.75,34.76,34.63]`    |
| SCARCE    |  42.563 | DIRECT | `[24912645,24258176,24203007]`    | `[0.5019/0.4981,0.5001/0.4999,0.5147/0.4853]` | `[0.5019,0.5001,0.5147]` | `[57.47,54.12,55.22]`    |
| SCARCE    |  42.563 | STAGED | `[25209879,24465686,24521304]`    | `[0.5102/0.4898,0.5072/0.4928,0.5234/0.4766]` | `[0.5102,0.5072,0.5234]` | `[54.27,55.19,56.19]`    |
| SCARCE    |  56.533 | DIRECT | `[20705532,20799621,20074849]`    | `[0.5035/0.4965,0.5056/0.4944,0.5030/0.4970]` | `[0.5035,0.5056,0.5030]` | `[72.38,70.50,69.66]`    |
| SCARCE    |  56.533 | STAGED | `[20441331,20728353,20999548]`    | `[0.5072/0.4928,0.4959/0.5041,0.5249/0.4751]` | `[0.5072,0.5041,0.5249]` | `[71.17,70.61,67.99]`    |
| SCARCE    |  70.689 | DIRECT | `[16963245,18252402,17674514]`    | `[0.5034/0.4966,0.4992/0.5008,0.5123/0.4877]` | `[0.5034,0.5008,0.5123]` | `[85.32,87.44,87.33]`    |
| SCARCE    |  70.689 | STAGED | `[17997977,18577263,18024251]`    | `[0.5088/0.4912,0.5020/0.4980,0.4998/0.5002]` | `[0.5088,0.5020,0.5002]` | `[84.26,81.58,83.93]`    |
| SCARCE    |  84.657 | DIRECT | `[14798274,15995925,15410584]`    | `[0.5077/0.4923,0.5120/0.4880,0.5063/0.4937]` | `[0.5077,0.5120,0.5063]` | `[98.97,100.50,98.99]`   |
| SCARCE    |  84.657 | STAGED | `[16259163,16315447,16317001]`    | `[0.5024/0.4976,0.4993/0.5007,0.5014/0.4986]` | `[0.5024,0.5007,0.5014]` | `[96.49,96.24,96.28]`    |
| SCARCE    | 154.621 | DIRECT | `[9656225,9659254,9687321]`       | `[0.5029/0.4971,0.4956/0.5044,0.4998/0.5002]` | `[0.5029,0.5044,0.5002]` | `[169.49,170.42,168.96]` |
| SCARCE    | 154.621 | STAGED | `[10217637,10316046,10136723]`    | `[0.5002/0.4998,0.5002/0.4998,0.4973/0.5027]` | `[0.5002,0.5002,0.5027]` | `[168.77,167.67,169.71]` |
| SCARCE    | 225.235 | DIRECT | `[6944312,7014719,6965722]`       | `[0.4991/0.5009,0.4997/0.5003,0.5001/0.4999]` | `[0.5009,0.5003,0.5001]` | `[240.95,241.71,242.27]` |
| SCARCE    | 225.235 | STAGED | `[7543134,7591361,7568193]`       | `[0.4999/0.5001,0.4999/0.5001,0.4998/0.5002]` | `[0.5001,0.5001,0.5002]` | `[239.36,237.70,238.50]` |
| SCARCE    | 449.914 | DIRECT | `[2710710,2689491,2724053]`       | `[0.4996/0.5004,0.5046/0.4954,0.5136/0.4864]` | `[0.5004,0.5046,0.5136]` | `[464.61,464.83,465.46]` |
| SCARCE    | 449.914 | STAGED | `[4080938,4043071,4079951]`       | `[0.4999/0.5001,0.5029/0.4971,0.4999/0.5001]` | `[0.5001,0.5029,0.5001]` | `[463.48,467.57,464.14]` |

Effective lanes are omitted outside the established 225 ns control because no independently
validated single-lane ceiling exists for the new points. At 225.235 ns, the matching isolated 4.440
million frames/s ceiling gives approximate aggregate `L` values of 1.874 DIRECT/plentiful, 1.653
STAGED/plentiful, 1.571 DIRECT/scarce, and 1.704 STAGED/scarce. These values agree with the balanced
completion evidence while quantifying the scarcity utilization difference.

### Existing work-cost signal verdict

The observed Core signal is monotonic within each fixed mode/source curve and is inexpensive in the
sense required here: fragment execution operations already use one start/end timing pair across one
or more frames, and reporting occurs at completed batch boundaries. It is therefore useful service
telemetry.

It is not a validated tier-neutral work-cost input. For the same 0.353 ns body and plentiful
sources, DIRECT reports approximately 8.1 ns/frame while STAGED reports approximately 18.3 ns/frame.
With the same DIRECT body, changing only two handles to one raises the estimate from approximately
8.1 to 22-24 ns/frame. The statistic includes the path and availability costs whose winner the
decision is supposed to predict. It can therefore map identical body work to different apparent work
costs and cannot independently supply the work-cost axis of this two-input surface.

H5 is rejected as stated. The obvious existing service-time statistic is insufficient without
decomposition. A future implementation investigation should prefer executor-body elapsed time
aggregated over a completed fragment batch, or prove that subtracting a separately measured
path/availability floor is stable; neither signal is implemented here.

### Hypothesis verdicts and supported tree

- H1: accepted. Under plentiful sources, DIRECT wins every cheap point and every tested point
  through 449.914 ns. Under scarcity, DIRECT is the clear winner at 21.566 ns; the cheaper 0.353 and
  7.666 ns aggregate means also favor DIRECT but have overlapping broad intervals.
- H2: accepted only under scarcity and falsified as a universal work-cost threshold. STAGED first
  becomes a clear winner at 84.657 ns and remains ahead at all larger scarce points. It never wins a
  plentiful point.
- H3: accepted. Source availability changes both the transition and the expensive asymptote.
- H4: accepted as an experimental branch candidate. The decision surface requires work cost plus
  independent pull availability relative to active workers; fixture labels are not the feature.
- H5: rejected as a tier-neutral input, as detailed above.

The smallest corrected decision tree supported at batch 32 is:

```text
independently pullable upstream opportunities sufficient for active workers?
    |
    +-- yes (two handles / two workers in this fixture)
    |       -> DIRECT across measured body costs 0.353-449.914 ns
    |
    +-- no (one shared handle / two workers in this fixture)
            |
            +-- body cost <= 21.566 ns
            |       -> DIRECT candidate region; clear at 21.566 ns,
            |          directionally favored but overlapping at cheaper points
            |
            +-- body cost 42.563-70.689 ns
            |       -> transition zone; no stable winner claimed
            |
            +-- body cost >= 84.657 ns
                    -> STAGED measured winner region
```

This is experimental structure, not a production policy. It does not authorize fixed thresholds,
source labels, or online probing.

Exactly one leaf remains next: identify and validate the runtime representation of
`independently pullable upstream opportunities / active execution workers`. The next bounded phase
should compare existing active-handle/upstream-count state with actual productive pull opportunities
under deterministic source counts while holding one cheap DIRECT-winning cost and one expensive
STAGED-winning cost fixed. Do not vary batch size, routing, or cache policy until that physical
availability input is shown to represent the branch.

### Verification results

-
`mise exec -- gradle :benchmarks:test --tests io.euhedral_execution.core.control_plane.FragmentPathCalibrationBenchmarkTest :benchmarks:assemble`
passed after formatting.
- The initial eight-point isolated run, 32-row forced-path run, two-point isolated drill-down, and
  eight-row forced-path drill-down all completed under the declared three-fork JMH protocol.
- `mise exec -- gradle build` passed all 64 repository tasks/checks.
- Stale-reference review found only intentional statements excluding the pre-fix lost-worker regime
  and prohibiting production policy.
- `git diff --check` and the explicit trailing-whitespace scan are clean.
- Final status contains only the four intended paths listed above; no production source, generated
  benchmark output, or user data changed.
