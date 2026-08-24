# Phase 1 H coarse execution handoff

Execute exactly the prepared H preset and comparison. No edits, additions, or policy decision.

- CONTINUOUS, workUnits=252, 23 workers/11 sources, productivity OFF.
- Only hPark: 0/5,000/25,000/50,000/250,000 ns.
- Two independent one-fork JVM replicas, complementary order, six 5-second windows.

```bash
mise exec -- gradle :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run benchmarks/src/main/presets/experiments/09-phase-1-h-idle-coarse.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare benchmarks/src/main/presets/comparisons/09-phase-1-h-idle-coarse.json
```

Mechanically verify configs, only hPark, productivity OFF, feeding, marginal band occupancy, and checksums. Return detailed fork/windows/body/contention/acquisition/idle telemetry and anomalies; no selection.
