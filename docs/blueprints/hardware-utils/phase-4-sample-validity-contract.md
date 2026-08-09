# Phase 4-A Sample and Validity Contract Blueprint

## Status and authority

This is the implementation-ready blueprint for P4-A, generated from the P4 parent blueprint.
The parent blueprint (`hardware-utils-overhaul/phase-4-pressure-monitor`) provides the architectural 
boundaries. This child blueprint defines the local inventory, data schemas, and state engines for 
`internal.sampling` required to fulfill the P4-A responsibility.

## Purpose

P4-A is responsible for:
- Immutable sample/validity SPI and canonical signals (`SignalValidity`, primitive signal records).
- Legacy compatibility adapters and compatibility-profile selection.
- Fixed-field delta, cache, and age state (`SampleStateEngine`, `SlowSampleCache`).
- Test fixtures for sampling and delta logic.

This blueprint does not touch pressure normalization/math (P4-B), listener publication (P4-C), or 
monitor lifecycle (P4-D).

## Sizing and split gate

The parent blueprint explicitly sized and split P4 into four children. P4-A represents a single, 
bounded responsibility (immutable schema + temporal delta/cache recovery + legacy compatibility). 
It uses one state engine, one slow cache, and one immutable boundary. It does not mix normalization 
math or JMM/concurrency concerns. The sizing gate confirms that P4-A fits into one implementation 
context and does not need further splitting.

## Implementation model reassessment

The parent blueprint evaluated the context envelope and selected `gpt-5.6-sol`, `high` effort.
P4-A requires implementing precise mathematical deltas (including reset/regression/wrap detection), 
detailed validity logic, and a fixed compatibility mapping. Given the amount of exact, stateful 
logic, `gpt-5.6-sol` at `high` reasoning effort is **confirmed** as the required implementation 
model.

## Local package inventory and contracts

Package: `io.euhedral_execution.hardware_utils.internal.sampling` (not exported).

### Validity and primitive signals

1. **`SignalValidity` (enum):**
   - `VALID`: Contains a valid physical payload.
   - `TRANSIENT_FAILURE`: Payload is canonical zero; retains last valid value in cache through TTL.
   - `UNSUPPORTED`: Payload is canonical zero; immediately clears baselines and smoother.

2. **Primitive Signals (immutable records):**
   - `CounterSignal(long value, long observedAtNs, SignalValidity validity)`
   - `LongGaugeSignal(long value, long observedAtNs, SignalValidity validity)`
   - `DoubleGaugeSignal(double value, long observedAtNs, SignalValidity validity)`
   - `BooleanSignal(boolean value, long observedAtNs, SignalValidity validity)`
   - `ThermalSignal(ThermalSeverity value, long observedAtNs, SignalValidity validity)`
   - Enforce payloads on non-valid states as canonical zero (`0`, `0.0`, `false`, `NOMINAL`).

### Fast and Slow Samples

3. **`CpuFastSignals`, `MemoryFastSignals`, `IoFastSignals` (immutable records):**
   - Must use compact constructors to deep-copy arrays or bitsets and validate ranges.
   - Ratios, if supported, must be in `[0.0, 1.0]`. Out-of-range ratios map to `TRANSIENT_FAILURE`.

4. **`FastHardwareSample` (immutable record):**
   - Contains `observedAtNs`, a fixed Euhedral logical CPU span, and copied effective-CPU set.
   - Contains system-wide fast signals and an array of `CpuFastSignals` (exact length matching span).

5. **`CpuSlowSignals`, `SystemSlowSignals`, `SlowHardwareSample`:**
   - Similar strict ownership, valid array lengths. Frequencies/capacity scalars are nonnegative.

6. **`DetailedSystemSnapshotProvider` (interface extends `SystemSnapshotProvider`):**
   - `FastHardwareSample sampleFast(long requestedAtNs)`
   - `SlowHardwareSample sampleSlow(long requestedAtNs)`

### Delta and Resolved Boundaries

7. **`SignalResolution` (enum):** `CURRENT`, `CACHED`, `BASELINE`, `UNAVAILABLE`.

8. **`CounterDelta`, `ResolvedLong`, `ResolvedDouble`, `LatencyInterval` (immutable records):**
   - `CounterDelta`: carries nonnegative `delta`, positive `elapsedNs` (if `CURRENT`/`CACHED`), and `resolution`.
   - `BASELINE`/`UNAVAILABLE` carry zero delta and zero elapsed time.

9. **`IntervalHardwareSample` (immutable record):**
   - Preserves same layout as fast/slow samples but contains resolved intervals and gauges. Deep-copied logical span/membership. This is the complete P4-A -> P4-B boundary.

### Compatibility Adapters

10. **`CompatibilityProfile` (enum):** `CANONICAL_PUBLIC`, `LINUX_V2_LEGACY`, `WINDOWS_LEGACY`, `MACOS_LEGACY`.

11. **`SystemSnapshotCompatibilityAdapter`:**
    - Selected once per monitor instance construction based on the provider type. No dynamic profile changes.
    - Honest bridge: validates `SystemSnapshot`, checked microsecond-to-nanosecond conversion (`Math.multiplyExact`), and explicitly marks missing signals as `UNSUPPORTED`.
    - `memoryLimit == Long.MAX_VALUE` maps to an unsupported internal limit.
    - `sampleSlow` returns an all-`UNSUPPORTED` slow sample timestamped at `requestedAtNs` without invoking wrapped provider.

### State Engine and Caches

12. **`SampleStateEngine`:**
    - Owns one fixed field per global counter, one array per CPU for CPU signals. No sidecars, `Map`, or `ThreadLocal`.
    - **Counter Rule:**
      - `dt = tc - tp`.
      - `c < p` or `dt <= 0` or missing baseline -> baseline established (resolution `BASELINE`), contribution `0`.
      - otherwise -> `delta = c - p`, evaluated over `dt` (resolution `CURRENT`).
    - **Timestamp Rule:**
      - Only strictly newer valid leaf timestamps replace existing gauge/ratio values.

13. **`SlowSampleCache`:**
    - Independent anchored grid. 5-second attempt grid, 15-second TTL.
    - Slow failure does not trigger retry or alter fast cadence.
    - Missing/unsupported immediately clears cache value.

## Constraints

- Deep-copy boundaries on all arrays and BitSets in constructors and accessors.
- TTL boundaries strictly enforce cache clearing: `[0, TTL]` is fresh. Over TTL -> clears value immediately.
- Failure conversion: nulls, non-finite bounds, mismatches -> `TRANSIENT_FAILURE` at the boundary.
- No editing of `ResourceMonitor` (except tests if needed/isolated), `SystemUtilization`, core, native code, or modules. No sidecars.

## Developer Review Summary
- **Purpose**: Implement immutable sampling records, validity mapping, delta evaluation, and legacy compatibility adapters for P4.
- **Boundaries**: Internal package `io.euhedral_execution.hardware_utils.internal.sampling`, fixed arrays, exact elapsed time computation.
- **Child Work Units**: Fast/slow signals, SampleStateEngine, SlowSampleCache, SystemSnapshotCompatibilityAdapter.
- **Implementation**: Confirmed `gpt-5.6-sol` at `high` reasoning effort.
- **Risks/Unresolved**: None. All definitions strictly follow P4 parent blueprint without structural changes.

## Completion Record

### Changed Files
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/enums/CompatibilityProfile.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/enums/SignalResolution.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/enums/SignalValidity.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/enums/ThermalSeverity.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/primatives/BooleanSignal.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/primatives/CounterDelta.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/primatives/CounterSignal.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/primatives/DoubleGaugeSignal.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/primatives/LatencyInterval.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/primatives/LongGaugeSignal.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/primatives/ResolvedDouble.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/primatives/ResolvedLong.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/samples/FastHardwareSample.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/samples/IntervalHardwareSample.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/sample/SlowHardwareSample.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/signals/CpuFastSignals.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/signals/CpuIntervalSignals.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/signals/CpuSlowIntervalSignals.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/signals/CpuSlowSignals.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/signals/IoFastSignals.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/signals/IoIntervalSignals.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/signals/MemoryFastSignals.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/signals/MemoryIntervalSignals.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/signals/SystemSlowIntervalSignals.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/signals/SystemSlowSignals.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/signals/ThermalSignal.java`
- 
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/DetailedSystemSnapshotProvider.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/SampleStateEngine.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/SlowSampleCache.java`
- `euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/internal/sampling/SystemSnapshotCompatibilityAdapter.java`
- `euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/internal/sampling/SamplingContractTest.java`

### Commands and Results
- `gradle :euhedral-hardware-utils:compileJava` - Successful.
- `gradle :euhedral-hardware-utils:test --tests io.euhedral_execution.hardware_utils.internal.sampling.SamplingContractTest` - All 2 sampling tests passed.
- `gradle :euhedral-hardware-utils:test` - Failed at `JniHeaderTest > usesTargetCorrectPlatformHeaders()` due to environmental limits (missing native headers/SDK in the current dev environment).

### Acceptance Evidence
The P4-A implementation explicitly addresses exact mathematical deltas, state evaluation mapping from immutable primitive samples to deltas/resolutions, and cache resolution through `SlowSampleCache` and `SampleStateEngine`. Tests verify the slow cache anchor/TTL logic and reset-on-regression rules in `SampleStateEngine`.

### Deviations
None. The code matches the parent blueprint precisely and strictly implements validity logic without cross-polluting P4-B's mathematical smoothing logic.

### Environmental Limits
The `gradle :euhedral-hardware-utils:test` suite failed on `JniHeaderTest` throwing `java.nio.file.NoSuchFileException`. This is a known environmental limit (as specified in `docs/AGENT_WORKFLOW.md` / `AGENTS.md`) caused by missing cross-target JNI headers/SDK required for native tests in the local run. It is treated as an external infrastructure condition, not a compilation or Java unit test failure caused by the P4-A implementation.
