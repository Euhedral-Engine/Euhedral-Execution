# Phase 1 qualified S coarse execution handoff

Execute exactly this preset and comparison; do not edit, add treatments, or decide policy.

- CONTINUOUS, qualified `workUnits=112`, 23 workers/11 sources.
- Productivity participation OFF (`productivityThresholdWeight=0`).
- Only `sPark`: 0, 5,000, 25,000, 50,000, 250,000 ns.
- Two one-fork JVM replicas, complementary order, six 5-second windows after two warmups.

```bash
mise exec -- gradle :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run benchmarks/src/main/presets/experiments/07-phase-1-s-idle-coarse.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare benchmarks/src/main/presets/comparisons/07-phase-1-s-idle-coarse.json
```

Verify everything mechanically, including marginal body-band occupancy per fork/window. Return detailed per-fork and ordered trajectories/cost/contention/acquisition/idle telemetry and anomalies. No selection.
