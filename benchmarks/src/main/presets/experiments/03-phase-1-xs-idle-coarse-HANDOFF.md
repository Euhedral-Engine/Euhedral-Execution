# Phase 1 XS coarse execution handoff

Execute this batch exactly as prepared. Do not add treatments or make a policy decision.

Fixture and invariants:

- lifecycle: `CONTINUOUS` only;
- body fixture: `workUnits=0`, the corrected true no-op, expected body band `XS`;
- topology: 23 physical workers from CPU set 2-31, 11 parallel repeating sources;
- productivity participation: `productivityThresholdWeight=0` in every completed trial;
- only treatment axis: `decisionWeights.idleTimeNs.xsPark` at 0, 1,000, 5,000, 15,000, and 50,000 ns;
- independent replication: two sweep samples, each with one JMH fork;
- execution order: balanced forward/reverse treatment pair;
- trajectory: six ordered 5-second measurement windows after two 2-second warmups.

Commands:

```bash
git status --short
mise exec -- gradle :benchmarks:test :benchmarks:assemble
mise exec -- benchmarks/build/bin/euhedral-calibration run \
  benchmarks/src/main/presets/experiments/03-phase-1-xs-idle-coarse.json
mise exec -- benchmarks/build/bin/euhedral-calibration compare \
  benchmarks/src/main/presets/comparisons/03-phase-1-xs-idle-coarse.json
```

After completion, mechanically verify:

1. Ten completed trial directories exist, each with one fork directory and six trajectory windows.
2. Every `trial_config.json` says `CONTINUOUS`, `workUnits=0`, and `productivityThresholdWeight=0`.
3. The five idle values each have sample indices 0 and 1; all non-XS idle values and all body thresholds are identical.
4. Every trajectory window has `continuouslyFed=true`.
5. Ordinary idle observations occupy body band XS; report any other observed body-band occupancy.
6. All `.sha256` files match their target files.
7. Preserve every fork and window and return mechanical results/anomalies only.

Do not change production constants, presets, source code, or experiment design.
