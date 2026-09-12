# Lower-agent handoff: frozen CACHE dynamic validation

Archived handoff, not current execution instructions. The 42-fork campaign has
completed; authoritative status and findings are in `policies/cache-scarce-v1.json`
(`STATIC_FROZEN_DYNAMIC_NEEDS_FOLLOWUP`). Production installation is documented
in `CACHE_FROZEN_POLICY.md`. The progress snapshot below predates both events.

Continue the user's freeze/migrate/dynamic-validation task in this repository.
Do not restart static optimization, retune coefficients, or change production defaults.
Preserve the existing dirty worktree and all experiment data. No commit or push.

## Done

- Stopped static tuning through its termination handler. The historical session
  `experiments/cache-scarce-loop` is preserved and marked `static-frozen.json`.
- Froze exact policy `policy-158a61afee6653cbbfde` from stored full-function JSON:
  `policies/cache-scarce-v1.json` and `policies/cache-scarce-v1-runtime.json`
  (paths relative to this Python project). Status is still
  `STATIC_FROZEN_DYNAMIC_UNVALIDATED`.
- SQLite backup migrated the canonical DB to `datasets/cache-policy-history.sqlite`.
  All stored values/schema match the retained source: 4,005 rows, 171 policies,
  27 campaigns, integrity `ok`; chosen policy has 148 measured forks.
  OFF-anchored network estimates: R7 +7.887%, R15 +9.482%, R23 +15.707%, broad +10.975%.
  DB, dataset README and both frozen artifacts are staged, not committed.
- Added generic `storePath` support with external-store resume checks and static/dynamic
  row separation. Added `policy_freeze.py`, `dynamic_validation.py`, and benchmark
  `DynamicPhaseSchedule`, reusing the existing continuous source/body setters.

## Run state and remaining work

The user asked the primary agent to stop executing and hand off. Eight of 42 JVM
forks completed under `experiments/cache-scarce-dynamic-v1`: the first OFF/frozen
pairs for R7 gate/body and R15 gate/body. The ninth attempt,
`runs/R23-gate-return-POLICY_OFF-fork-0`, was interrupted deliberately. Preserve it.
Confirm no old validation JVM remains, then move only that incomplete directory
outside `runs/` into an `interrupted/` directory before resuming. Completed forks
are verified and skipped by `run`.

The user's latest measurement correction is already configured: eight five-second
windows per phase, three phases (40 seconds per regime, two-minute trajectory).
Seven fixtures: R7/R15/R23 `S1 -> SR -> S1` at W0 and `W0 -> W576 -> W0` at S1;
R15 additionally `S1 -> S4 -> S1` at W0. Two arms, three independent forks each.
Recovery: two consecutive windows within 5% of the final four-window mean.
Windows are nested observations, not independent forks.

Preliminary single-pair evidence: late plentiful throughput was about 22% below OFF
on R7 and 11% below OFF on R15. Do not call this a final result or retune. Review
all forks, especially gate exit, owner-local productive-handle refresh and retained
adaptive contention half-life. Throughput alone cannot establish which mechanism
causes a deficit or prove CPU participation/control chatter.

Before continuing, review the implementation and rerun focused tests. Previously
51 Python tests passed, plus four tests after increasing window duration; 10 benchmark
and 48 core timing/gate tests passed, as did assembly and Spotless. Two additional
freeze/artifact tests and a null-label import guard were added afterward and still
need their focused rerun. No benchmarks should overlap tests/builds.

From repository root:

```bash
(cd python/pareto-weight-calibration && ../../.venv-cache-tuner/bin/python -m pytest -q tests/test_policy_freeze.py tests/test_dynamic_validation.py tests/test_parameter_loop.py tests/test_scarce_loop.py tests/test_loop_reporting.py)
mise exec -- gradle :benchmarks:assemble
.venv-cache-tuner/bin/python -m pareto_weight_calibration.dynamic_validation run --config python/pareto-weight-calibration/tasks/cache-scarce-dynamic-v1.json
```

Finish all bounded forks, review generated per-window/phase/fork/transition tables,
write `review.json` with `result` (PASS or NEEDS FOLLOW-UP) and `findings`, then run
the same command with `collect` instead of `run` to regenerate summaries. Update
only frozen metadata to `STATIC_FROZEN_DYNAMIC_VALIDATED` or
`STATIC_FROZEN_DYNAMIC_NEEDS_FOLLOWUP`, recording the validation path and precise
findings. Keep coefficients identical. Inspect diff/status, stage the updated
policy metadata, and hand back paths, fork counts, recovery findings and rerun command.

More detail: [CACHE_FROZEN_POLICY.md](CACHE_FROZEN_POLICY.md).
