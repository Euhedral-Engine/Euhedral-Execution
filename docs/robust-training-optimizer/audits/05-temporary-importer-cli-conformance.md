# Phase 5 Blueprint Conformance Audit

Audited branch: `agent/phase5cd-temporary-importer-cli-audit`, based on cleaned Phase 5B commit
`17b6182` and the Phase 5C/D verifier correction.

Blueprint:
`docs/robust-training-optimizer/blueprints/05-temporary-importer-cli.md`.

## Result

**Does not yet fully conform.** The implementation has the approved isolated importer, strict
typed configuration path, and documentation boundary, and all executed tests pass. However, the
blueprint makes several specific parser, filesystem-failure, configuration, stop-path, and command
tests mandatory. Those tests are absent, so their acceptance criteria remain unverified. This
audit makes no architectural recommendation.

## Requirement classification

| Requirement group | Status | Evidence |
| --- | --- | --- |
| Removable importer boundary, marker, explicit off-by-default command, and one production caller | Satisfied | `CurrentWorkspaceImporter` is confined to `importer.currentworkspace`; only `Runner` imports it. The prescribed dependency and marker searches found no Phase 1-4 dependency. |
| Exact current-layout inventory, no filename inference, regular/unsafe/unmapped reporting, and vector-only import | Satisfied | `CurrentWorkspaceLayout` contains the sixteen settled paths; `CurrentWorkspaceImporter.discover` walks only the two specified roots without following links and rejects every unrecognised or unsupported member. The importer creates only `PolicyVector` catalog/bootstrap artifacts and rejects alternating-file measurements with the settled reason. |
| Strict legacy token parsing, bit preservation, per-file transactional handling, deterministic catalog/bootstrap/report, and atomic-only publication | Satisfied | `LegacyDecimalBitReader` enforces signed-long ASCII rows and final LF; `PolicyVector`/`PolicyRegistry` preserve exact lanes and deduplicate in unsigned ID order. The importer validates staged output, writes `COMPLETE` last, and uses `ATOMIC_MOVE` with owned cleanup. The verifier additionally preserved malformed-file path/line/token diagnostics in the warning path while retaining the stable report reason. |
| Importer acceptance and failure test matrix | Unverified | `CurrentWorkspaceImporterTest` covers representative alternating/vector-only/summary/unknown inputs, signed zero, malformed width, deterministic output across two parents, target collision, and insufficient catalog. It does not cover the mandated symlink and unsupported-type cases, odd rows, overflow, non-finite lanes, BOM/final-LF variants, duplicate/collision handling, shuffled creation order, streaming boundary, injected write/read-back/move failures, or all listed malformed grammar cases. |
| Strict typed closed-loop configuration, complete key ownership, defaults, relative paths, and no ambient-property input | Satisfied | `training.config.ClosedLoopConfigCodec` declares the blueprint key set, rejects unknown/duplicate/empty entries, resolves paths from the configuration file, maps values into the settled immutable records, and contains no system-property/environment read. `ClosedLoopConfig` normalizes the stop path and preserves its exclusion from `ClosedLoopConfigFingerprint`. |
| Configuration validation and fingerprint test matrix | Unverified | `ClosedLoopConfigCodecTest` covers a minimal config, selected overrides, unsigned seeds, and selected malformed inputs. It does not prove every declared key maps to its record component; repeated-order semantics, normalized duplicate paths, all malformed key/value forms, constructor failures, fingerprint exclusion/inclusion matrix, or environmental non-interference. |
| Stop/resume behavior, result disclosure, exact CLI forms, usage text, and stable `package-run` meaning | Satisfied | `Runner` accepts only the settled command flag orders, calls the typed `ClosedLoopRunner`, reports stage/checkpoint/package/awaiting scenarios, and labels retained pooled commands as legacy. `ProductionServices.stopRequested` uses no-follow regular-file inspection and propagates inspection I/O errors; `ClosedLoopConfigFingerprint` excludes operational stop, active-environment, and resume fields as settled. |
| Stop/CLI integration test matrix | Unverified | `RunnerTest` tests the importer happy path and malformed command forms only. It does not exercise typed closed-loop result logging, help content, package-run dispatch, stop-file semantics, no-implicit-import behavior across other commands, or the configured runtime resume/stop boundaries. |
| README and architecture documentation, including ranking, coverage, calibration, budgets, package/reproduction, cross-environment bootstrap, and deletion recipe | Satisfied | `euhedral-training/README.md` and `docs/ML_CLOSED_LOOP_ARCHITECTURE.md` describe the typed operation, vector-only importer limitation, native bootstrap evidence, robust ordering, resume/stop, packages, and searchable deletion recipe. They retain the Phase 4 audit limitation. |
| Memory semantics, memory pollution, numerical precision, Phase 1-4 compatibility, and user-workspace isolation | Satisfied | Import state is single-owner and publishes only immutable results through atomic rename; legacy measurements are validated then discarded. The reader/writer use 128 KiB buffered streaming, lanes remain raw 64-bit encodings, and no Phase 1-4 schema or user-owned input/output file changed. The full Phase 1-5 training suite passed. |

## Validation evidence

Prompt 5C/D ran on 2026-07-28:

```text
mvn -B -pl euhedral-training -am install -Dmaven.test.skip=true
  BUILD SUCCESS (6 reactor modules)

mvn -B -pl euhedral-training \
  -Dtest=CurrentWorkspaceImporterTest,ClosedLoopConfigCodecTest,RunnerTest,ClosedLoopRunnerTest test
  8 tests, 0 failures, 0 errors, 0 skipped

mvn -B -pl euhedral-training test
  122 tests, 0 failures, 0 errors, 1 skipped
```

The sole skip is the existing opt-in `ScenarioOrdinalNetworkIntegrationTest`; its DJL environment
is outside this deterministic Phase 5 surface. `git diff --check` passed. The importer-boundary and
ambient-property searches were clean; the current-layout search had only the temporary package,
Runner, and the intentionally retained pooled-v0 default scheduled for Phase 7.

## Handoff

Return the missing deterministic test cases to the approved Phase 5 blueprint/implementation path
before declaring Phase 5 complete. The source behavior audited here needs no new design decision:
the blueprint already fixes the required grammar, mappings, atomicity outcomes, configuration
matrix, stop behavior, command output, and deletion-boundary assertions.

## Naming and package cleanup verification

The 2026-07-29 organization pass moves `ClosedLoopConfigCodec` and its test into
`training.config`, beside `ClosedLoopConfig`, and updates inherited scheduling references to the
`scheduling.io` package. The temporary importer remains one isolated
`training.importer.currentworkspace` removal subtree. The full training suite passes after these
moves: 122 tests, 0 failures, 0 errors, and the one pre-existing opt-in DJL integration test
skipped.

The moves change no recognized legacy path, import transaction, configuration key, stop/resume
transition, memory ownership, raw-bit conversion, numerical precision, or package schema. The
audit classification above is therefore unchanged.
