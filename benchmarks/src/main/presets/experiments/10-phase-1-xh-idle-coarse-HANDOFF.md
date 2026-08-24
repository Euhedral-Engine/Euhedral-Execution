# Phase 1 XH coarse execution handoff

Execute exactly the XH preset and comparison. No edits, additions, or decision.

- CONTINUOUS, workUnits=384, 23 workers/11 sources, productivity OFF.
- Only xhPark: 0/5,000/25,000/50,000/250,000 ns.
- Two independent one-fork JVM replicas, complementary order, six 5-second windows.

```bash
mise exec -- gradle :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run benchmarks/src/main/presets/experiments/10-phase-1-xh-idle-coarse.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare benchmarks/src/main/presets/comparisons/10-phase-1-xh-idle-coarse.json
```

Mechanically verify configs, only xhPark, productivity OFF, feeding, marginal XH occupancy, checksums. Return detailed fork/window body/contention/acquisition/idle telemetry and anomalies; no selection.
