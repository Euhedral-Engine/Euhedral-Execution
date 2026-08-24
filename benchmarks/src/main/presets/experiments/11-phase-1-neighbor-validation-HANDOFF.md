# Phase 1 neighboring-source validation handoff

Execute the prepared portability matrix and comparison exactly. No edits, extra fixtures, or decision.

- CONTINUOUS true-no-op XS body; productivity OFF.
- 8 and 16 parallel sources; XS 0 vs selected 50,000 ns.
- S/M/H/XH fixed at selected 0 ns.
- Two independent JVM replicas per cell in balanced complementary order.

```bash
mise exec -- gradle :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run benchmarks/src/main/presets/experiments/11-phase-1-neighbor-validation.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare benchmarks/src/main/presets/comparisons/11-phase-1-neighbor-validation.json
```

Verify eight trials/forks, configs, only source count/XS idle axes, productivity OFF, fed windows, XS occupancy, and checksums. Return mechanical per-fork/trajectory telemetry and candidate-vs-control deltas by source count; no policy decision.
