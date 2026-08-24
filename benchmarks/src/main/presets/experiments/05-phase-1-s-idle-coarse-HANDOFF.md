# Phase 1 S coarse execution handoff

Execute exactly the prepared S preset and comparison; do not edit, add treatments, or decide policy.

- `CONTINUOUS`, `workUnits=96`, 23 workers, 11 sources.
- `productivityThresholdWeight=0` in every trial.
- Only `sPark` varies: 0, 5,000, 25,000, 50,000, 250,000 ns.
- Two one-fork JVM replicas in balanced forward/reverse order; two 2-second warmups and six 5-second windows.

```bash
mise exec -- gradle :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run benchmarks/src/main/presets/experiments/05-phase-1-s-idle-coarse.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare benchmarks/src/main/presets/comparisons/05-phase-1-s-idle-coarse.json
```

Verify configs, only `sPark` varying, S ordinary-idle occupancy (report any spill), continuously fed windows, productivity OFF, and all checksums. Return mechanical per-fork and ordered-window throughput plus body cost, contention/acquisition/idle telemetry and anomalies; make no selection.
