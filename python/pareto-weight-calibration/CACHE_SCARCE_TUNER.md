# Automatic CACHE scarce-policy tuner

The selected static session is frozen. For future explicitly authorized tuning,
start a **new** output session with the local scarcity gate and scarce-only objective:

```bash
scripts/tune_cache_scarce_policy.sh --output-dir experiments/cache-scarce-next --dry-run
scripts/tune_cache_scarce_policy.sh --output-dir experiments/cache-scarce-next
scripts/tune_cache_scarce_policy.sh --output-dir experiments/cache-scarce-next --resume
```

The wrapper uses Mise and `.venv-cache-tuner/bin/python`, or `CACHE_TUNER_PYTHON`.
FIT and PROPOSE run in separate spawned processes and exit before benchmarking,
releasing their CPU/GPU allocations. Interrupted proposal work resumes from the
saved phase and reuses completed fit artifacts. Search reports include per-model
prediction timing and separate host/GPU working-memory estimates. See the
[resource behavior](AUTOMATIC_CACHE_TUNER.md#resources) for details.
The equivalent generic command is:

```bash
mise exec -- .venv-cache-tuner/bin/python -m pareto_weight_calibration.training_runner \
  --task python/pareto-weight-calibration/tasks/cache-scarce-loop.json \
  --output-dir experiments/cache-scarce-next
```

Append `--resume` or `--max-rounds N` as needed. `--smoke` uses a separate output
session and reduced timings; smoke measurements never enter normal history.
No production default or winning coefficient is installed by this command.

## Runtime boundary

`CacheTimingConfig.DEFAULT` uses the exact frozen
timing function. Historical constructors preserve their false gate default.
The calibration JSON switch is
`cacheScarcityGateEnabled`. With it enabled, the local gate is:

```text
registeredWorkers > 0 && productiveHandles >= registeredWorkers -> PLENTIFUL
otherwise                                                     -> SCARCE
```

These are the existing owner-local productive-handle observations and the
registered-worker denominator used by PHR. Unknown handles retain the estimator's
existing optimistic treatment. This is not a source-count threshold or an estimate
of how many other workers are sleeping. A plentiful fixture can temporarily become
locally scarce when handles stop producing.

Plentiful observations skip CACHE participation reduction, including timing
inference and parking for that path. The existing body/contention DIRECT versus
STAGED rule still applies. Local cached work drains before path selection, and
remote cached work remains eligible afterward. Contention evidence updates and
prospective decay are unchanged. The frozen participation classifier still makes
scarce decisions. No global state or hot-path allocation was added.

## Measurements and references

The default task retains the established physical R7/R15/R23 placements and uses
only S1/W0, S1/W96, S1/W576 at each topology: nine scarce fixtures, two independent
blocks. Normal warmup, measurement, invocation target and timeout are unchanged.
Seven new policies (four guided, two local random, one global random) plus up to
two deduplicated incumbent policies receive the full panel. OFF runs once on
R15-S1-W96, for **163 forks per default round**, or 145 with one incumbent.
Four full rounds project 652 forks excluding operational retries. This is a
projection, not an additional JVM budget.

OFF is the historical fixed 15,000/1,000,000 ns control with timing inference and the
new gate disabled. Its sentinel throughput and change from the previous sentinel
are reported only as coarse drift diagnostics. It supplies no missing workload
baseline and does not enter candidate ordering.

All live policies are compared to the same remeasured primary incumbent, matched
by campaign, workload and block. Reported round percentages are **versus that
named incumbent**, not POLICY_OFF. Raw fork throughput, nested windows, exact
functions, references and commands remain available. Missing matches are never
filled with another campaign's control.

Historical scarce rows are read once from `experiments/cache-idle-loop/forks.sqlite`
into the new store. The original store is opened read-only and is never rewritten.
The explicit compatibility bridge covers this calibration fixture's single
upstream source: productive handles cannot exceed one, below R7/R15/R23; the
registered-worker <=1 startup path was already DIRECT. Thus enabling this gate
cannot alter its scarce decision. Configuration, placement, finite measurements
and original five-window means are checked. Plentiful and incompatible rows remain
in the source database. The import audit records inclusion and exclusion counts.

The generic offline model takes candidate coordinates **and an exact reference
configuration description** as inputs. Old OFF-relative returns and new
incumbent-relative returns retain distinct reference features. Reference features
are offline only; they do not add runtime coefficients. Predictions used to
compare proposals all use the same current primary reference. Held-policy folds
also exclude training ratios whose denominator is a held policy, preventing
reference measurements from leaking into validation. Campaign diagnostics remain
optional. The three runtime families remain separate and compatible zero-plane
observations can train more than one family without duplicating stored forks.

## Scarce preference and search ancestry

The four separate objectives are uncapped broad scarce log gain, positive scarce
workload fraction, minimum topology log gain, and worst scarce workload log gain.
The scalar heuristic uses capped broad gain, penalizes negative minimum-topology
returns and material worst-workload losses using the JSON tolerances. This avoids
allowing one huge positive workload to conceal a large hole. Plentiful targets do
not enter scoring, risk, uncertainty penalties or Pareto ranking in this task.
Guided proposals compete globally; there are no fixed per-family quotas. Random
exploration does not need a favorable surrogate prediction.

Every local proposal stores its generating family, parent policy, parent radius,
parent and candidate normalized coordinates, distance, edge fraction and role.
Training compatibility does not determine ancestry. The recorded parent drives
updates:

- Interior improvement: shrink its inherited radius by 0.7, bounded by minimumRadius.
- Edge improvement: retain its inherited radius and move the center.
- Retained center: shrink its radius by `search.shrink`, bounded by
  `search.minimumRadius`, independently of moves accepted for other centers.
  Reports use `RETAIN_SHRINK`, or `RETAIN_MIN_RADIUS` at the minimum. Stagnation
  still increments when the round has no accepted practical improvement.
- Global or alternate measured basin: use the explicit newBasinRadius.

`update.json`, the terminal and `round-summary.json` state the decision, parent,
distance, edge fraction and old/new radii. Resume preserves that lineage. The
active beam has at most two centers; a separate five-policy elite archive survives
beam eviction. Historical elite comparisons reconcile matched log-ratio edges
through a per-workload OFF-anchored measurement network. These are labeled network
estimates, not freshly matched OFF results; repeated campaigns contribute separate
edges. They guide seeds/restarts and diagnostics. Within-round acceptance always
uses direct matched incumbent measurements. Elite membership alone does not cause
another benchmark. No production winner is implied by the beam or archive.

Rounds proceed automatically. Optional `search.stopPatience` can stop measured
stagnation only when all active radii reach the minimum; one unsuccessful round or
stable predictions do not stop the search. Bounded measured-region restarts remain
available before that point.

## Durable output

`current-best.json`, `current-best-config.json`, `leaderboard.tsv`, `elite-archive.json`,
round summaries and exact arm/proposal definitions expose the accepted result.
The config file contains the timing function; using the new regime behavior also
requires `cacheScarcityGateEnabled: true` in calibration or the corresponding
runtime `CacheTimingConfig` flag.

Successful raw attempts are cleaned only after every fork is parsed, committed to
SQLite, summarized and UPDATE is durably committed. Failed/interrupted logs remain.
The old session and historical archives are untouched.
