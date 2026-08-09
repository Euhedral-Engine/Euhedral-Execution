# Phase 6-A Windows Topology & GLPIEx Parser Blueprint

## 1. Status and Authority

- **Parent Plan**: [`docs/plans/hardware-utils-platform-parity-overhaul.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/plans/hardware-utils-platform-parity-overhaul.md)
- **Parent Blueprint**: [`docs/blueprints/hardware-utils/phase-6-windows-platform.md`](file:///home/bagotay/src/euhedral/Euhedral-Execution/docs/blueprints/hardware-utils/phase-6-windows-platform.md)
- **P6 Root Branch**: `hardware-utils-overhaul/phase-6-windows`
- **Child Blueprint Branch**: `hardware-utils-overhaul/phase-6-windows-topology-blueprint`
- **Child Implementation Branch**: `hardware-utils-overhaul/phase-6-windows-topology-implementation`
- **Audit File Target**: `docs/audits/hardware-utils/phase-6-windows-topology-model-conformance.md`
- **Owning Module**: `euhedral-hardware-utils`
- **Selected Blueprint Model**: `gpt-5.6-sol` with `high` reasoning effort
- **Status**: Complete. Implementation finalized and verified.

This child blueprint is subordinate to `AGENTS.md`, `docs/AGENT_WORKFLOW.md`, `docs/ARCHITECTURE.md`, and the parent P6 blueprint (`phase-6-windows-platform.md`). It translates the parent topology contracts into an explicit, implementable specification for `WindowsSystemLayout` and `win32.*` GLPIEx binary structure parsers.

## 2. Objective & Core Defects Addressed

The objective of **Phase 6-A** is to deliver a robust, truthful, win32 `GetLogicalProcessorInformationEx` (GLPIEx) binary buffer parser (`win32.*`) and Windows topology provider (`WindowsSystemLayout`) that eliminates legacy defects and satisfies the P2 topology snapshot model.

### Core Defect Corrections

- **Defect T03 Correction (GLPIEx Structure Header Double-Advancement)**:
  - Header of `SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX` is 8 bytes (`Relationship` DWORD at offset 0, `Size` DWORD at offset 4).
  - The record's `Size` field specifies total length including the 8-byte header.
  - Parser must advance the byte buffer position by `Size` bytes from the record start, NOT `Size + 8` bytes.
- **Multi-Group Processor Mapping**:
  - Convert Windows `(group, processor)` coordinates bijectively into Euhedral global logical CPU IDs: `logicalId = group * 64 + processor`.
  - Reverse mapping: `group = (short)(logicalId / 64)`, `processor = (int)(logicalId % 64)`.
- **Bit 63 KAFFINITY Mask Math Safety**:
  - Processor 63 within a group corresponds to bit 63 in KAFFINITY mask (`0x8000000000000000L`).
  - Bit shift math uses unsigned long operations: `1L << processor`.
  - Bit testing uses `(mask & (1L << bit)) != 0L` to prevent sign-extension traps when bit 63 is set.
- **P-Core / E-Core Classification**:
  - Read `PROCESSOR_RELATIONSHIP.EfficiencyClass` byte at payload offset 1.
  - Value 0 indicates E-core (or homogeneous topology); value >0 indicates P-core performance class.
  - Map cores to `CoreKind.PERFORMANCE` vs `CoreKind.EFFICIENCY`, or `CoreKind.UNKNOWN` when scores are homogeneous across the system.
- **SMT Detection**:
  - Read `PROCESSOR_RELATIONSHIP.Flags` byte at payload offset 0.
  - Bit 0 (`LTP_PC_SMT` = 0x01) indicates SMT hyperthreading execution context.
- **Multi-Group Cache Domains**:
  - Extract cache relationships (`CACHE_RELATIONSHIP`) and assemble `BitSet` masks spanning global logical IDs across all active processor groups.
- **Malformed & Truncated Buffer Validation**:
  - Enforce bounds checks before reading structure headers and payload arrays.
  - Throw `IllegalArgumentException` with offset diagnostics on malformed buffers, triggering P2 fallback topology creation cleanly.

## 3. Scope & Non-Goals

### 3.1. In Scope

- **Primary Source Files**:
  - [`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/WindowsSystemLayout.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/WindowsSystemLayout.java)
  - [`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/win32/SystemLogicalProcessorInformation.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/win32/SystemLogicalProcessorInformation.java)
  - [`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/win32/ProcessorRelationship.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/win32/ProcessorRelationship.java)
  - [`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/win32/CacheRelationship.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/win32/CacheRelationship.java)
  - [`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/win32/GroupAffinity.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/win32/GroupAffinity.java)
  - [`euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/win32/Relationship.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/main/java/io/euhedral_execution/hardware_utils/windows/win32/Relationship.java)
- **GLPIEx Binary Buffer Parsing**: Precise structure layout offsets, little-endian byte ordering, bounds verification.
- **Bijective Logical ID Translation**: `(group, processor)` to `logicalId` conversion.
- **Cache & Core Topology Construction**: Mapping `PROCESSOR_PACKAGE`, `PROCESSOR_CORE`, and `CACHE` relationships into `TopologyInput`.
- **Testing & Binary Fixtures**: Binary GLPIEx fixtures and unit test suite in [`WindowsTopologyFixtureTest.java`](file:///home/bagotay/src/euhedral/Euhedral-Execution/euhedral-hardware-utils/src/test/java/io/euhedral_execution/hardware_utils/windows/WindowsTopologyFixtureTest.java).

### 3.2. Non-Goals

- Modifying Windows process resource extraction, Job Object CPU rate limits, or working set memory metrics (owned by P6-B).
- Modifying JNI C++ code (`windows_system_layout.cpp`), Win32 thread affinity, dynamic symbol loading, or PE ABI hardening (owned by P6-C).
- Modifying common `TopologyBootstrap` or `TopologyNormalizer` logic in `internal.topology`.
- Modifying `euhedral-core` fragment loops or execution schedulers.
- Any inspection or modification of `euhedral-training`.

## 4. Architectural Contracts & Implementation Checklist

```text
  Win32 GetLogicalProcessorInformationEx (GLPIEx Raw Byte Buffer)
                                |
                                v
     SystemLogicalProcessorInformation.parse(ByteBuffer buffer)
     - Validate header bounds (rem >= 8, Size >= 8, pos + Size <= capacity)
     - Extract Relationship (DWORD at pos+0), Size (DWORD at pos+4)
     - Advance loop by Size bytes per record (fixing Defect T03)
                                |
        +-----------------------+-----------------------+
        |                                               |
        v                                               v
ProcessorRelationship.parse(...)              CacheRelationship.parse(...)
- Flags (BYTE at offset 8)                    - Level (BYTE at offset 8)
- EfficiencyClass (BYTE at offset 9)          - LineSize (WORD at offsets 10..11)
- GroupCount (WORD at offsets 30..31)         - CacheSize (DWORD at offsets 12..15)
- GroupMask (GROUP_AFFINITY array at 32)      - GroupMask (GROUP_AFFINITY array at 40)
        |                                               |
        +-----------------------+-----------------------+
                                |
                                v
                      WindowsSystemLayout
- Map (group, processor) bijectively to logicalId = group * 64 + processor
- Bit 63 KAFFINITY mask math safety (mask & (1L << bit)) != 0L
- Classify CoreKind (PERFORMANCE / EFFICIENCY / UNKNOWN)
- Build CacheDomain BitSet masks across processor groups
                                |
                                v
    TopologyInput("windows", List<LogicalCpu>, List<CacheDomain>)
                                |
                                v
    TopologyBootstrap.normalize(provider, availableProcessors, logger, "windows")
                                |
                                v
  TopologyModel (Immutable, Sparse OS CPU ID Maps, P2 Cache Fallbacks)
```

### 4.1. Checklist Item 1: Exact GLPIEx Structure Offset Parsing (`win32.*`)

- [ ] **`SystemLogicalProcessorInformation` Header Parsing**:
  - Buffer order MUST be set to `ByteOrder.LITTLE_ENDIAN`.
  - While `pos < limit`, read `Relationship` value (`buffer.getInt(pos)`) and `Size` value (`buffer.getInt(pos + 4)`).
  - Bounds check: Verify `limit - pos >= 8`, `Size >= 8`, and `pos + Size <= limit`. If violated, throw `IllegalArgumentException`.
  - Calculate payload offset: `payloadPos = pos + 8`.
  - Dispatch parsing to specific relationship parsers based on `Relationship.from(relValue)`:
    - `PROCESSOR_CORE` or `PROCESSOR_PACKAGE` -> `ProcessorRelationship.parse(buffer, payloadPos, Size - 8)`
    - `CACHE` -> `CacheRelationship.parse(buffer, payloadPos, Size - 8)`
  - Advance outer buffer position by EXACTLY `Size` bytes: `pos += Size` (fixing defect T03).
- [ ] **`ProcessorRelationship` Layout Offsets** (from structure start offset `pos`):
  - Struct payload starts at offset 8 of EX record:
    - Offset 8: `Flags` (BYTE). Bit 0 (`LTP_PC_SMT` = 0x01) indicates SMT hyperthreading.
    - Offset 9: `EfficiencyClass` (BYTE). 0 = E-core/homogeneous, >0 = P-core.
    - Offsets 10..29: `Reserved[20]` (BYTE[20], 20 bytes).
    - Offsets 30..31: `GroupCount` (WORD, 2 bytes, unsigned short).
    - Offset 32: `GroupMask` array consisting of `GroupCount` `GROUP_AFFINITY` elements (16 bytes each).
  - Bounds check: Verify payload length satisfies `payloadLen >= 24 + GroupCount * 16`.
- [ ] **`CacheRelationship` Layout Offsets** (from structure start offset `pos`):
  - Struct payload starts at offset 8 of EX record:
    - Offset 8: `Level` (BYTE). 1 = L1, 2 = L2, 3 = L3.
    - Offset 9: `Associativity` (BYTE).
    - Offsets 10..11: `LineSize` (WORD, 2 bytes, unsigned short).
    - Offsets 12..15: `CacheSize` (DWORD, 4 bytes, signed/unsigned int).
    - Offsets 16..19: `Type` (DWORD, 4 bytes). 0 = Unified, 1 = Instruction, 2 = Data.
    - Offsets 20..37: `Reserved[18]` (BYTE[18], 18 bytes).
    - Offsets 38..39: `GroupCount` (WORD, 2 bytes, unsigned short) or single group mask header.
    - Offset 40: `GroupMask` array consisting of `GroupCount` `GROUP_AFFINITY` elements (16 bytes each).
  - Bounds check: Verify payload length satisfies `payloadLen >= 32 + GroupCount * 16`.
- [ ] **`GROUP_AFFINITY` Struct Layout** (16 bytes total):
  - Offsets 0..7: `Mask` (KAFFINITY, ULONGLONG, 8 bytes, little-endian `long`).
  - Offsets 8..9: `Group` (WORD, 2 bytes, unsigned short).
  - Offsets 10..15: `Reserved[3]` (6 bytes).

### 4.2. Checklist Item 2: Bit 63 KAFFINITY Mask Math & Unsigned Shift Invariants

- [ ] **Bit 63 Shift Invariant**:
  - KAFFINITY mask is a 64-bit unsigned bitmask where bit `p` (0 <= p <= 63) represents processor `p` within a processor group.
  - Bit shift calculation MUST use `1L << p`.
  - For processor 63, `1L << 63` produces `0x8000000000000000L` (signed `Long.MIN_VALUE`).
- [ ] **Bit Testing Invariant**:
  - When checking if processor `p` is present in mask, NEVER test `(mask & (1L << p)) > 0L`. For processor 63, this evaluates to `false` because the result is negative.
  - MUST test using non-zero comparison: `(mask & (1L << p)) != 0L`.
- [ ] **Mask Iteration Invariant**:
  - Iterating set bits in a KAFFINITY mask MUST use `Long.numberOfTrailingZeros(mask)` and bit clearing `mask &= mask - 1`.
  - This operates correctly regardless of whether bit 63 is set.

### 4.3. Checklist Item 3: Bijective `(group, processor)` to Logical ID Mapping

- [ ] **Forward Translation**:
  - Convert Windows group and processor index to global Euhedral logical CPU ID:
    ```java
    long logicalId = (long) group * 64L + (long) processor;
    ```
  - Validate that `logicalId` fits within standard integer bounds (`0 <= logicalId < Integer.MAX_VALUE`).
- [ ] **Reverse Translation**:
  - Given global logical ID `cpuId`:
    ```java
    short group = (short) (cpuId / 64);
    int processor = cpuId % 64;
    long mask = 1L << processor;
    ```
- [ ] **Grouping Invariants**:
  - Group 0 processors 0..63 map to logical IDs 0..63.
  - Group 1 processors 0..63 map to logical IDs 64..127.
  - Group `g` processors 0..63 map to logical IDs `g * 64 .. g * 64 + 63`.

### 4.4. Checklist Item 4: P/E Core Classification via `EfficiencyClass` & SMT Detection

- [ ] **SMT Classification**:
  - `ProcessorRelationship.smt` boolean is `true` if `(flags & 0x01) != 0`, `false` otherwise.
- [ ] **EfficiencyClass Extraction**:
  - `ProcessorRelationship.efficiencyClass` stores the unsigned byte value from payload offset 1 (`EfficiencyClass`).
  - Flag `pCore` is set to `true` if `efficiencyClass > 0`, `false` if `efficiencyClass == 0`.
- [ ] **System-Wide CoreKind Classification**:
  - Inspect all `PROCESSOR_CORE` relationships in `WindowsSystemLayout.translate()`.
  - Evaluate if system has heterogeneous efficiency classes:
    - `hasPCores = cores.stream().anyMatch(c -> c.pCore);`
    - `hasECores = cores.stream().anyMatch(c -> !c.pCore);`
  - Classification assignment per core:
    - If `hasPCores && hasECores`: Cores with `pCore == true` become `CoreKind.PERFORMANCE`; cores with `pCore == false` become `CoreKind.EFFICIENCY`.
    - If ALL cores have `pCore == false` (homogeneous or legacy OS): All cores become `CoreKind.UNKNOWN`.
    - If ALL cores have `pCore == true`: All cores become `CoreKind.UNKNOWN`.

### 4.5. Checklist Item 5: Cache Domain `BitSet` Masks Spanning Multi-Group Logical IDs

- [ ] **Instruction Cache Exclusion**:
  - Filter out instruction caches (`cache.type == CacheType.INSTRUCTION`).
  - Only collect data (`DATA`) and unified (`UNIFIED`) cache relationships.
- [ ] **Multi-Group Cache Mask Assembly**:
  - For each valid cache relationship, initialize `BitSet cacheMask = new BitSet()`.
  - Iterate over `groupAffinities` list inside `CacheRelationship`.
  - For each `GroupAffinity`:
    - Extract `group = Short.toUnsignedInt(affinity.group())`.
    - Extract `mask = affinity.mask()`.
    - Iterate set bits in `mask`: for each bit `p`, calculate `logicalId = group * 64 + p`, and call `cacheMask.set((int) logicalId)`.
  - Construct `CacheDomain(level, cacheSizeBytes, lineSize, cacheMask)`.

### 4.6. Checklist Item 6: Bounds Checking & Malformed Buffer Error Handling

- [ ] **Buffer Guard Checks**:
  - If `rawData == null` or `rawData.length == 0`, `parse()` returns an empty `List.of()`.
  - In `WindowsSystemLayout`, an empty relationship list yields `TopologyInput("windows", List.of(), List.of())`.
  - `TopologyBootstrap.normalize()` automatically applies the conservative fallback model (1 socket, 1 core, 1 CPU, synthesized L1/L2/L3).
- [ ] **Truncated Buffer Handling**:
  - Check buffer capacity before reading struct fields.
  - If header indicates `Size > buffer.remaining()` or `Size < 8`, throw `IllegalArgumentException("Malformed GLPIEx buffer at offset " + pos)`.
  - If payload parsing encounters array overrun (`GroupCount * 16 > payloadLen`), throw `IllegalArgumentException`.

## 5. Sizing & Split Gate Assessment

### Sizing Evaluation

1. **Context Load**: The implementation is strictly bounded to `WindowsSystemLayout.java`, `win32.*` struct classes, and `WindowsTopologyFixtureTest.java`. The total context involves GLPIEx binary parsing, bit 63 math, multi-group ID mapping, and P2 `TopologyInput` integration. This comfortably fits within the working memory of a single implementation pass.
2. **Single Responsibility**: `WindowsSystemLayout` and `win32.*` own Windows GLPIEx topology discovery and parsing. Resource collection (P6-B) and native JNI/affinity (P6-C) are cleanly separated.
3. **Independent Validation**: Windows topology parsing can be fully validated using binary GLPIEx buffer fixtures and unit tests without requiring a live Windows machine or JNI C++ libraries.

**Conclusion**: Child P6-A is irreducible, correctly sized, and ready for implementation in a single pass.

## 6. Implementation Model Reassessment

- **Required Capabilities**: Intricate binary buffer offset calculations, bit 63 KAFFINITY mask math, multi-group logical ID mapping, P/E core classification logic, and P2 topology model normalization integration.
- **Selected Model**: **`gpt-5.6-sol` with `high` reasoning effort**.
- **Justification**: Preserving strict GLPIEx structure alignment, little-endian binary buffer safety, bit 63 mask traps, and accurate multi-group CPU ID mapping across Windows 10/11 systems requires high reasoning effort.

## 7. Developer-Review Summary

| Item | Details |
|---|---|
| **Purpose** | Deliver win32 GLPIEx binary buffer parsing (`win32.*`) and Windows CPU topology mapping (`WindowsSystemLayout`) supporting multi-group systems, bit 63 mask math, P/E core classification, and P2 topology normalization. |
| **Package Boundaries** | `io.euhedral_execution.hardware_utils.windows.WindowsSystemLayout` (Java), `io.euhedral_execution.hardware_utils.windows.win32.*` (Java Parsers). |
| **Key Invariants** | GLPIEx outer loop advances by `Size` bytes per record (fixing T03 double-header defect); bijective mapping `logicalId = group * 64 + processor`; bit 63 math uses `(mask & (1L << bit)) != 0L`; P/E core classification uses `EfficiencyClass`; instruction caches excluded; malformed buffers throw `IllegalArgumentException` triggering P2 fallbacks. |
| **Child Action Items** | P6-A implementation: `hardware-utils-overhaul/phase-6-windows-topology-implementation`. |
| **Selected Model** | `gpt-5.6-sol` with `high` reasoning effort for implementation and conformance audit. |
| **Principal Risks** | Incorrect byte offset calculation causing buffer underflow/overflow; bit 63 signed long shift traps; incorrect multi-group cache bitset alignment. |
| **Unresolved Items** | None. Struct offsets, bit math, mapping formulas, core classification, and fallbacks are fully specified. |

## 8. Verification & Acceptance Criteria

### 8.1. Acceptance Criteria

1. **Exact Offset Advancement (Defect T03 Fix)**:
   - GLPIEx parser advances buffer position by `Size` bytes per record.
   - Given a multi-record GLPIEx binary blob, all records are parsed sequentially without alignment drift or `BufferUnderflowException`.
2. **Multi-Group Bijective Logical ID Mapping**:
   - Given GLPIEx records spanning Group 0 and Group 1, logical CPU IDs map to `group * 64 + processor`.
   - Logical CPU 64 corresponds to Group 1 Processor 0.
3. **Bit 63 Mask Math Safety**:
   - Given a KAFFINITY mask with bit 63 set (`0x8000000000000000L`), processor 63 is correctly identified and set in CPU and cache bitsets.
4. **P/E Core Classification & SMT Detection**:
   - Given a GLPIEx record with non-zero `EfficiencyClass` values, cores are classified into `PERFORMANCE` vs `EFFICIENCY`.
   - Given homogeneous `EfficiencyClass` values (all 0 or all >0), all cores are classified as `UNKNOWN`.
   - `SMT` flag is correctly extracted from `Flags` bit 0.
5. **Cache Domain BitSet Assembly**:
   - Multi-group cache relationships assemble `BitSet` masks spanning global logical IDs across processor groups.
   - Instruction caches are excluded from `CacheDomain` output.
6. **Malformed Buffer Error Handling**:
   - Truncated or invalid GLPIEx byte buffers throw `IllegalArgumentException`.
   - Empty or null raw buffers fall back cleanly to P2 single-socket fallback topology model.

### 8.2. Verification Commands

```bash
# Build hardware-utils module and run Windows topology fixture tests
gradle :euhedral-hardware-utils:test --tests "io.euhedral_execution.hardware_utils.windows.WindowsTopologyFixtureTest"

# Run all hardware-utils tests
gradle :euhedral-hardware-utils:test
```

## 9. Completion Record

- **Date**: 2026-08-06
- **Branch**: `hardware-utils-overhaul/phase-6-windows-topology-implementation`
- **Implementation Highlights**:
  - Corrected GLPIEx structure header double-advancement defect (T03) in `SystemLogicalProcessorInformation.java` by advancing byte position strictly by record `size` bytes.
  - Implemented bijective Windows group + processor to global logical CPU ID mapping (`logicalId = group * 64 + processor`) in `WindowsSystemLayout.java`.
  - Added Bit 63 KAFFINITY mask safety handling (`(mask & (1L << bit)) != 0L`) across group affinity loops and logical ID mapping.
  - Implemented heterogeneous P-core vs E-core classification using `EfficiencyClass` (payload offset 1) in `ProcessorRelationship.java` and `WindowsSystemLayout.translate()`.
  - Added `CacheType.INSTRUCTION` exclusion and multi-group cache domain `BitSet` mask generation in `CacheRelationship.java` and `WindowsSystemLayout.java`.
  - Enforced strict buffer bounds and payload length checks across all `win32.*` parsers, throwing `IllegalArgumentException` on malformed/truncated buffers to trigger P2 topology fallbacks.
- **Verification Results**:
  - `WindowsTopologyFixtureTest`: Executed single-group, multi-group, >64 CPU, bit 63, P/E core classification, cache domain extraction, and malformed buffer fixture tests. Passed 100%.
  - `gradle :euhedral-hardware-utils:test`: Full module build and test suite passed successfully.
