# Phase 1 XS refinement execution handoff

Execute exactly the prepared `04-phase-1-xs-idle-refinement.json` preset and its comparison. Do not edit files, add treatments, or decide policy.

- `CONTINUOUS`, `workUnits=0`, 23 workers, 11 parallel sources.
- Productivity participation explicitly OFF: `productivityThresholdWeight=0`.
- Only `xsPark` varies: 25,000; 50,000; 100,000; 250,000; 500,000 ns.
- Two sweep samples, one fork each, balanced forward/reverse order.
- Two 2-second warmups and six ordered 5-second measurement windows.

```bash
mise exec -- gradle :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run \
  benchmarks/src/main/presets/experiments/04-phase-1-xs-idle-refinement.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare \
  benchmarks/src/main/presets/comparisons/04-phase-1-xs-idle-refinement.json
```

Verify ten trials/forks, sixty windows, completed configs, productivity OFF, XS-only occupancy, continuous feeding, only `xsPark` varying, and every checksum. Return mechanical per-fork and ordered-window throughput plus contention/acquisition/idle telemetry and anomalies. Make no selection.
