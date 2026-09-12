> The scarce-only revision uses a separate task/session.
> See [CACHE_SCARCE_TUNER.md](CACHE_SCARCE_TUNER.md) for the local gate, matched-incumbent references,
> one OFF sentinel and explicit radius lineage. Existing idle-loop data remains historical.

# Automatic CACHE idle-policy tuner

The generic `parameter_loop` task fits the existing surrogate tournament, generates coefficients,
benchmarks them, appends independent forks, and moves a measured search beam automatically. It
never installs a production policy. Runtime templates and parameter bounds are immutable task
inputs; local centers and radii are session state. There are no ablation or review-receipt gates.

## Start and resume

Create a persistent environment once using the repository Mise Python and install this package:

```bash
mise exec -- python -m venv .venv-cache-tuner
.venv-cache-tuner/bin/python -m pip install -e 'python/pareto-weight-calibration[tournament,dev]'
scripts/tune_cache_policy.sh --dry-run
scripts/tune_cache_policy.sh --output-dir experiments/cache-idle-loop
scripts/tune_cache_policy.sh --resume --output-dir experiments/cache-idle-loop
```

`CACHE_TUNER_PYTHON` may point to another installed environment. The wrapper runs through Mise,
including the benchmark build and launcher. The equivalent generic entrypoint is:

```bash
.venv-cache-tuner/bin/python -m pareto_weight_calibration.training_runner \
  --task python/pareto-weight-calibration/tasks/cache-idle-loop.json \
  --output-dir experiments/cache-idle-loop
```

Use `--max-rounds 1` to bound a session further. `--smoke` uses a separate output directory by
default, reduced warmup/measurement settings, one fixture, one exploration policy and one round.
Smoke results never append to historical archives or normal session storage. Explicitly name a
separate directory if supplying `--output-dir` with `--smoke`.

The default task has seven new policies per round (four guided, two local random, one global
random), at most two measured incumbents, and OFF. The 18 fixtures cover R7/R15/R23, one or R
sources, and work units 0/96/576. Two balanced blocks give at most 360 JVM forks per round and
1,440 planned forks across four rounds, excluding retries. Max rounds is the only tuning
budget: there is no JVM-attempt, session wall-clock, or stagnation stop limit. The 240-second
per-trial timeout, two attempts per trial, and three-consecutive-failure safeguard remain.
Attempt counts are recorded for progress only. Legacy `jvmAttempts`, `wallHours`, and
`stagnationPatience` settings are ignored, including when resuming older sessions.
No full campaign runs during installation or dry-run.

## Resources

JSON `execution` and matching CLI overrides configure `device`, `fitWorkers`, `predictWorkers`,
`backend`, `nativeThreadsPerWorker`, `memoryBudgetGiB`, and `gpuConcurrency`. CLI options use
hyphens, for example `--fit-workers 8 --predict-workers 8 --device cuda --memory-budget-gib 48`.
Auto workers resolve allowed CPU affinity, cgroup quota, and memory limits. Families share one
pool; folds and estimators do not multiply the worker budget. Processes fit Python-heavy models;
threads evaluate native predictors. Large process inputs use joblib read-only memmaps.

CUDA prediction adapters evaluate fitted linear/polynomial, local linear, MLP, kernel and GP algebra using
float64 tensors. Supported XGBoost/CatBoost implementations use their native GPU backends.
Unsupported estimators retain parallel CPU evaluation, with backend/fallback reports. Parsing
`--device cuda` does not convert every estimator into a GPU model. Auto uses CPU fitting on tiny
training sets and considers native CUDA fitting above `cudaMinFitRows`; prediction crossover is
`cudaMinPredictRows`. Independent fitted output models share the prediction worker pool; a
multi-output fit is still evaluated once per batch. Local regression uses weighted sufficient
statistics and batched solves, preserving its fitted regularization and query-dependent weights.
Host worker and GPU working memory are budgeted separately. Search reports include per-fit
prediction timings; the terminal identifies the largest prediction cost after each region.

FIT and PROPOSE each run in a spawned, owned process. The controller waits for that process to
exit before advancing, releasing CPU allocators, CUDA contexts and native library workspaces
before JMH. Persistent pools are reused within a phase. No CUDA-initialized process is forked.
Interrupts terminate the offline process group and preserve the last saved phase for resume.
Every CUDA prediction adapter is owned by its execution pool. Input tensors are cached only
within an explicit prediction batch; closing the pool releases adapter tensors and unused
PyTorch allocator reservations after workers finish, including failed batches. CUDA context and
library workspace memory can remain visible during an offline phase; its process exits at the
phase boundary. Direct library callers that keep their own Python process alive may still see
that context memory after closing an adapter.

## Search and measured decisions

All compatible history is imported once into `forks.sqlite`. The archive remains unchanged. The
importer checks runtime/configuration semantics, topology fixture and schedule identity, requires
throughput-only output, and re-parses original measurement regions. Warmup lines are excluded.
Exact policies stay grouped across campaigns. Repeated forks remain separate records. The shared
4D zero plane informs the two separate 5D parameterizations without creating duplicate forks.

Model targets use same-campaign, same-block OFF log returns. Exact historical reference-relative
returns are retained separately; changing incumbent deltas never become one common model target.
The JSON declares arbitrary parameter paths and response outputs, model grids and validation.
Campaign-transfer diagnostics are optional. Model fits are cached only within a completed round's
unchanged data, with fitted preprocessing scoped to each actual estimator.

The search allocates up to `2^24` points across active regions, with an optional bounded increase
when scoring is cheap and the last batch still improves the predicted frontier. It keeps a bounded
coordinate-diverse reservoir rather than sorting millions of points or storing every prediction.
Concurrent model/transform/kernel working memory determines inference batch size. Selected exact
configs and all their predictions/disagreement are retained. Random proposals do not need predicted
feasibility or Pareto membership. If guidance is unavailable, valid random points fill its slots.

Both ranking and measured center updates use the task's practical preference: capped broad scarce
log gain, a penalty for negative minimum-topology scarcity, and mean plentiful harm below the
configured -3% material-loss threshold. Breadth, topology minimum and harm are also retained as
separate objectives. No single huge positive fixture has unlimited weight. Small plentiful losses
are tolerated. Per-workload, per-block returns and both observed minima remain visible.

Incumbents are remeasured in every round. A measured improvement greater than the configured
practical tolerance may move the beam; an interior improvement shrinks radius by 0.7, while an
edge improvement keeps radius. Retained centers shrink their own radius by `search.shrink`,
bounded by `search.minimumRadius`, and keep exploration. New basins start at their configured radius.
Spacing scales with radius. Repeated failures to improve try an alternate measured region before
the round limit. No historical absolute baseline is borrowed to decide a round winner.

## State and outputs

`state.json` is atomically replaced at phase and trial boundaries. A session file lock prevents
concurrent execution. SQLite enforces unique fork ingestion. Resume collects a finished but
uncollected trial once, skips completed trials, and retains failed attempts separately. Interrupts
and timeouts terminate the owned launcher process group and preserve partial logs. Build output
is reused throughout a session; only JSON policies change between rounds.

Each `round-NNN` contains fitted models/validation summaries, candidate configs and predictions,
exact arm/trial commands, compact fork records, `throughput.tsv`, and measured beam updates.
Successful raw attempts are cleaned after the completed round is durably summarized; unsuccessful
attempts retain their diagnostic logs by default.
`best-measured.json` retains the final measured beam and observed archive. Runtime defaults remain
unchanged. Task semantics cannot change on resume; resource overrides and an increased round limit
can. Cleanup options can also change on resume. To change the objective or runtime template, start a
new session.

Historical strict replay tools still enforce their original locks. This loop does not require
historical lock files, source allowlists, review receipts, or full archive restoration. Generated
session files and the local Python environment are ignored by Git; historical data is not deleted.

## Progress, ETA, and the current answer

Normal output shows each phase and each trial's actual round-plan position, policy, workload,
block, attempt, elapsed time, and status. A retry is explicitly labeled. Long-running launchers
print a heartbeat every 30 seconds. Round benchmark ETA is based on observed attempt durations,
including slow outcomes, and is labeled approximate. Before observations exist it says
"estimating". FIT reports family/fold progress and remaining-family estimates; PROPOSE reports
region point counts and estimated remaining scoring time. Estimates are not throughput guarantees,
and trial ETA does not include future offline fitting or unknown retries.

Every completed UPDATE prints `ROUND BEST` separately from `CURRENT INCUMBENT`. A round winner
that misses the practical improvement threshold does not replace the accepted incumbent. The
view reports measured, same-round OFF-relative topology percentages, broad geometric changes,
scarce workload breadth, worst scarce/plentiful workloads, and exact named active coordinates.
The score is a diagnostic. Reporting neither changes the score nor feeds the optimizer.

The direct answers at the session root are:

```bash
cat experiments/cache-idle-loop/current-best.json
cat experiments/cache-idle-loop/current-best-config.json
```

Both files are replaced atomically. The first describes the best accepted measured incumbent;
the second contains only its exact runtime timing-function configuration. With a beam, the
summary lists all next search centers. If the existing restart policy chooses an alternate
historical exploration center, it is listed separately from the accepted measured answer.

`round-NNN/round-summary.json` and `round-summary.txt` retain the structured and printed summary.
`leaderboard.tsv` contains each measured policy/campaign result, winner/incumbent flags, scores,
percentages and worst workloads. This is a cross-round trajectory view, not a matched comparison
between campaigns. `measured-ranking.json`, `throughput.tsv`, `update.json`, and `proposals.json`
remain the detailed evidence.

## Successful raw-trial cleanup

The JSON task accepts:

```json
"cleanup": {
  "successfulRawTrials": true,
  "retainFailedTrials": true
}
```

These are the normal defaults; smoke defaults to retaining raw attempts. Cleanup removes successful
attempt directories under that round's `trials/` only after every fork is parsed and persisted in
SQLite, compact fork/plan/command artifacts and both summaries exist, and UPDATE is committed.
`trial-plan.json` retains the benchmark definition, ordered arms/fixtures, exact commands and
attempt
statuses. `forks.json` and SQLite retain exact configurations, all nested measurement windows and
raw throughput. Raw provenance paths in compact records can therefore refer to deleted successful
attempts. Historical archives and other campaigns are never cleanup targets.

Failed and interrupted attempts remain for diagnosis, even when a later attempt succeeds.
Explicitly setting `retainFailedTrials` to false also permits removing those attempts after their
round fully succeeds; their status/error metadata remains. Setting `successfulRawTrials` to false
retains all raw output. Changing cleanup options does not change optimizer task semantics.

The summary records removed successful-attempt count, retained unsuccessful-attempt count, bytes
removed and errors. Deletion intent is saved before removal. Resume retries pending/failed cleanup
from SQLite and compact files without rerunning or reparsing deleted successful trials. A cleanup
failure is reported and does not invalidate collected scheduler evidence.
