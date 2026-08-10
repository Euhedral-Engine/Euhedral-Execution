# Phase 8 Control Plane Integration and Release Validation Record

## Overview

This validation record documents the verification results for Phase 8 of the Hardware-Utils Platform
Parity Initiative: **Control Plane Integration and Release Conformance**.

- **Phase Branch**: `hardware-utils-overhaul/phase-8-core-release`
- **Audit Branch**: `hardware-utils-overhaul/phase-8-core-release-audit`
- **Parent
  Blueprint**: [docs/blueprints/hardware-utils/phase-8-control-plane-integration-release.md](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-8-control-plane-integration-release.md)
- **Target Conformance
  Audit**: [docs/audits/hardware-utils/phase-8-control-plane-integration-release-conformance.md](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/audits/hardware-utils/phase-8-control-plane-integration-release-conformance.md)

---

## Acceptance Criteria and Test Coverage

### 1. Pressure-to-Batch Curve and Attenuation (Defect C01)

-
**Formula**: $C (p) = \text{clampLong} (\text{Math.round} (\text{eligibleMax} - p \cdot (\text{eligibleMax} - 2)), 2, \text{eligibleMax})$
where $\text{eligibleMax} = \max (2, \min (\text{maxBatchSize}, \text{frameQuota}))$.
- **Validation**:
    - `adaptiveBatchCapCalculatedFromUnattenuatedPressure`:
      Verified $p=0.0 \implies 4096$, $p=0.5 \implies 2049$, $p=1.0 \implies 2$.
    - `adaptiveBatchCapClampedToAtLeastTwo`: Verified cap never drops below 2 under high pressure or
      low quota bounds.
    - P/E attenuation multiplier (`0.5`/`0.7`) verified completely removed.

### 2. Lock-Free Monotonic Timestamp Linearization and Hot-Loop Read

- **Validation**:
    - `LAST_ACCEPTED_TIMESTAMP_NS` acquire/CAS loop prevents out-of-order snapshot overwrites under
      concurrent background monitor updates.
    - `ADAPTIVE_BATCH_CAP.setRelease(...)` ensures proper memory publication boundary.
    - `ADAPTIVE_BATCH_CAP.getOpaque(...)` primitive read in `getBatchLimit()` ensures zero heap
      allocations, zero locks, and zero formatting overhead in `cycle()`.

### 3. Snapshot Sanitization and Edge Safety

- **Validation**:
    - `nanOrInfinitePressureIgnored`: Rejected NaN and Infinite floating-point values without
      corrupting batch limits.
    - `nullOrEmptyCpuSnapshotsIgnored`: Rejected null snapshots, empty CPU snapshot arrays,
      out-of-bounds CPU indices, and null `CpuSnapshot` entries.

### 4. Cache Delegation and Hysteresis (Defect C02)

- **Validation**:
    - `ControlPlaneCache.java` production source code verified untouched (test-only scope).
    - Delegated `super.update(snapshot)` executes only on accepted monotonic updates.
    - Combined fast attack ($\tau \approx 0.90$ s) and slow recovery ($\tau \approx 9.90$ s) EWMA
      hysteresis verified in `ControlPlaneCacheTest`.

### 5. Monitor-to-Lattice Component Integration

- **Validation**:
    - `resourceMonitorUpdatesPropagateToControlPlaneFragment`: Live component interaction verified
      in `ControlPlaneLatticeTest`.

### 6. Non-Training Module Build Isolation and Native Binary Gates

- **Validation**:
    - `NativeBinaryGateTest`: Universal native libraries (`x86_64`, `aarch64` for Linux, Windows,
      macOS) cross-compiled via Zig 0.16.0 verified for symbol visibility and ABI compatibility.
    - macOS binaries verified signed via `rcodesign`.
    - Non-training Gradle build: All 6 modules (`core`, `hardware-utils`, `data-structures`,
      `hashing`, `reactor-core`, `spring-core`) compiled and tested with zero errors.

---

## Command Results and Evidence Log

```bash
# Command 1: Focused Core Unit and Integration Tests
mise exec -- gradle :euhedral-core:test
# Result: BUILD SUCCESSFUL

# Command 2: Multi-Module Non-Training Test Suite
mise exec -- gradle :euhedral-core:test :euhedral-hardware-utils:test :euhedral-data-structures:test :euhedral-hashing:test :euhedral-reactor-core:test :euhedral-spring-core:test
# Result: BUILD SUCCESSFUL in 42s

# Command 3: Multi-Module Non-Training Full Assembly and Packaging
mise exec -- gradle :euhedral-core:build :euhedral-hardware-utils:build :euhedral-data-structures:build :euhedral-hashing:build :euhedral-reactor-core:build :euhedral-spring-core:build
# Result: BUILD SUCCESSFUL in 4s
```

---

## Environmental Boundaries and Limits

- **Authoring Environment**: Linux x86_64 container.
- **JDK Version**: OpenJDK 21.0.2 (via `mise.toml`).
- **Gradle Version**: 9.6.1 (via `mise.toml`).
- **Unverified External Gates**: Physical Windows and macOS host execution is unverified inside
  Linux container environment; satisfied by developer-attested cross-platform CI workflow.
