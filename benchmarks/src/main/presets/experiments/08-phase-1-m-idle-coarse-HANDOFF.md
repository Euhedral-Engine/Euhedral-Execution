# Phase 1 M coarse execution handoff

Execute exactly the prepared M preset and comparison. No edits, added treatments, or decisions.

- CONTINUOUS, workUnits=172, 23 workers/11 sources, productivity OFF.
- Only mPark: 0/5,000/25,000/50,000/250,000 ns.
- Two one-fork JVM replicas in complementary order; six 5-second windows after two warmups.

```bash
mise exec -- gradle :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run benchmarks/src/main/presets/experiments/08-phase-1-m-idle-coarse.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare benchmarks/src/main/presets/comparisons/08-phase-1-m-idle-coarse.json
```

Verify configs, only mPark, productivity OFF, continuous feeding, marginal body-band occupancy, and all checksums. Return detailed mechanical per-fork/windows, body cost, contention/acquisition/idle telemetry, and anomalies; no selection.
