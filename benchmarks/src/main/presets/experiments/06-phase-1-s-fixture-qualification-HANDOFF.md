# Phase 1 S fixture qualification handoff

Run exactly this short diagnostic. It is not an idle calibration and must not produce a policy decision.

```bash
mise exec -- gradle :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run benchmarks/src/main/presets/experiments/06-phase-1-s-fixture-qualification.json
```

Verify four one-fork CONTINUOUS trials at work 104/112/120/128, productivity threshold zero, unchanged Phase 1 starting-reference idles, three fed windows, and checksums. Report ordinary-idle body-band occupancy and measured raw/smoothed body costs by work value. Do not edit files or add work values.
