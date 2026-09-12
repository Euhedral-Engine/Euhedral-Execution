# Frozen CACHE scarce policy and dynamic validation

The selected policy is `policy-158a61afee6653cbbfde`, family `park-body`.
`policies/cache-scarce-v1.json` retains its exact full stored timing function,
active parameter values, OFF-anchored static evidence, migration verification,
and validation status. `policies/cache-scarce-v1-runtime.json` contains only the
runtime function. The named policy is looked up explicitly; the current beam
leader is never substituted.

## Production default

`CacheTimingConfig.DEFAULT_FUNCTION` is the single immutable Java owner of the
exact frozen runtime function. Default fragment configuration uses it with the
scarcity gate enabled: plentiful observations bypass learned CACHE withdrawal.
Java production code does not read JSON. Explicit timing-function overrides
remain supported; the two-argument timing constructor or an explicit null
function selects fixed timing, including benchmark POLICY_OFF. Historical
constructor gate behavior remains compatible.

Production installation does not change the evidence status:
`STATIC_FROZEN_DYNAMIC_NEEDS_FOLLOWUP`. The completed 42-fork validation and its
qualified findings remain in the policy metadata. No additional tuning or
benchmarking was performed to install these defaults.

The static session is closed. Its `static-frozen.json` marker prevents accidental
restart, including `--resume`. State, retained rounds, summaries, leaderboard,
elite archive, current-best artifacts, and interrupted-attempt diagnostics remain
under `experiments/cache-scarce-loop`. Prior successful raw-log cleanup is not
reversed; all currently retained artifacts are preserved.

The canonical durable history is `datasets/cache-policy-history.sqlite`.
The generic loop accepts a configured `storePath` directly. Future authorized
tuning work would use a new session directory with that database; the closed
historical session is not reopened. This task does not generate new static rounds.

## Dynamic panel

`tasks/cache-scarce-dynamic-v1.json` fixes the policy, fixture panel, replication,
window durations and recovery definition. Seven fixtures, two arms, and three
independent JVM forks per arm produce 42 forks. Each fork retains its workers,
source objects, contention/body history and caches across three phases:

- R7/R15/R23: W0, enabled sources `1 -> R -> 1`.
- R7/R15/R23: one source, body work `W0 -> W576 -> W0`.
- R15: W0, enabled sources `1 -> 4 -> 1`, always scarce.

Each phase has eight five-second measurement windows (40 seconds); the complete
trajectory is two minutes. Three two-second warmups stay in the initial phase.
Each fixture/replicate block pairs FROZEN_POLICY with actual POLICY_OFF
(`cacheTimingFunction: null`, fixed park 15000 ns and half-life 1000000 ns).
Order reverses between replicate blocks. Both arms retain the explicit scarcity
gate and the static campaign's other runtime settings and physical CPU mapping.

The harness uses `euhedral.calibration.dynamicSchedule` for explicit phase JSON,
extending the existing source-enable and shared body-work setters. Legacy
`participationDynamicScenario` fixtures remain supported unchanged. Only the
benchmark harness reads the stimulus; runtime policy code receives no fixture
configuration. Explicit schedules require CONTINUOUS lifecycle and throughput-only
JMH mode, with one JVM per trial. This is validation, with no fitting or search.

Recovery means two consecutive windows within 5% of the last four-window mean
of the new phase. The reported window is the end of the qualifying run, counted
from one after the transition. A recovered low-throughput state can still be a
regression; matched OFF deltas are therefore reported separately. Windows are
nested observations, never independent replicates. No scalar score or numerical
production acceptance gate is applied.

## Commands

From the repository root, after activating the Python tuner environment:

```bash
.venv-cache-tuner/bin/python -m pareto_weight_calibration.policy_freeze \
  --task python/pareto-weight-calibration/tasks/cache-scarce-loop.json \
  --source experiments/cache-scarce-loop/forks.sqlite \
  --destination python/pareto-weight-calibration/datasets/cache-policy-history.sqlite \
  --policy-id policy-158a61afee6653cbbfde \
  --artifact python/pareto-weight-calibration/policies/cache-scarce-v1.json

mise exec -- gradle :benchmarks:assemble
.venv-cache-tuner/bin/python -m pareto_weight_calibration.dynamic_validation run \
  --config python/pareto-weight-calibration/tasks/cache-scarce-dynamic-v1.json
```

Before launching, the validator prints and verifies both databases, all rows,
campaign/policy counts, integrity, exact frozen identity, measured fork count,
and OFF-anchored estimates. `prepare` performs that verification and prints the
bounded trial/time projection without benchmarking. `run` verifies completed
forks and skips them on resume; incomplete attempts remain preserved and require
a new output directory. To repeat all measurements, add
`--output experiments/cache-scarce-dynamic-v1-rerun-01` using a new directory.
`collect` regenerates descriptive tables from retained logs without executing JVMs.

Outputs under `experiments/cache-scarce-dynamic-v1` include `dynamic-summary.md`,
`dynamic-summary.json`, `per-fork.tsv`, `per-phase.tsv`, `per-window.tsv`,
`transition-summary.tsv`, frozen/OFF inputs, resolved per-fork configurations and
raw logs. Dynamic results are not inserted into the static database.

Review examines each transition, independent-fork spread, matched OFF deficits,
recovery, and return-to-initial throughput. Throughput-only results do not directly
establish CPU participation or identify gate/H/body state; mechanistic attribution
must remain qualified. The final review records PASS or NEEDS FOLLOW-UP, and
updates only policy validation metadata, never its coefficients or production
defaults.
