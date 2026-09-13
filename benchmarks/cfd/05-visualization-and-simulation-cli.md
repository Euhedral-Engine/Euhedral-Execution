# Phase 05 - Visualization and simulation workflow

Prerequisite: 04. Read the [master plan](README.md), the field-extraction section of [NUMERICS.md](NUMERICS.md), and the official VTK XML format reference linked there.

## Deliverable

One command runs a complete 3D simulation and produces a ParaView-readable time series of the actual fluid fields.

## Changes

- Complete the `simulate` CLI with explicit backend, step/time limits, output directory, export cadence, and configuration overrides. Validate overrides through the same resolver as JSON input.
- Write uncompressed binary VTI ImageData using streamed appended raw data, explicit byte order, and UInt64 block headers/offsets. Do not build giant XML strings or hold a full time series in memory. Offer ASCII only as a tiny-fixture/debug mode.
- Represent the solver samples as CellData. For `nx*ny*nz` cells, use point extents `0 nx 0 ny 0 nz`, matching physical origin/spacing, and X-fastest array order. Output velocity as three components, density, gauge pressure, and solid/obstacle labels. Derive values from completed post-collision populations with the correct force correction.
- Add PVD collection output with the actual physical time, or explicitly labeled lattice time when no physical conversion exists. Write frames through temporary files and add them to the collection only after the frame is complete.
- Export synchronously at a completed-step barrier initially. Never let the solver overwrite a population buffer while an exporter reads it. Do not add an unbounded asynchronous snapshot queue.
- Persist a small resolved-config JSON and metrics CSV containing step/time, validity diagnostics, mass/flux, forces, simulation time, and output time. Existing output directories must not be overwritten by default. On failure, retain only completed frames and write a failed status with the cause.
- Provide `VISUALIZATION.md` with exact ParaView steps for reading the PVD file, displaying obstacle masks, slicing velocity/pressure, and generating streamlines. Explain Cell Data to Point Data where a filter requires it. A streamline of one frame is not a particle trajectory through an unsteady time series.
- Do not add Java native VTK bindings, an embedded renderer, a web frontend, or a ray-marching demo. ParaView is a viewer, not the numerical backend.

## Acceptance

Test a hand-constructed non-cubic field with distinct XYZ components so transposition, component order, and half-cell offsets are detectable. Verify binary header lengths, offsets, cell counts, obstacle labels, physical units, and PVD times. Include a mocked/streamed large-offset test without allocating a multi-gigabyte file.

Round-trip a small file through an available VTK/ParaView reader and document the exact command. If that external viewer is unavailable, report that check as unrun; internal byte-level tests do not prove viewer interoperability by themselves.

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend serial --output benchmarks/cfd/build/runs/duct-demo
```

The result must be a usable 3D flow dataset. No screenshot or animation is required to validate the numerical backend, and file output must remain outside later scheduler timing comparisons.

## Implementation prompt

```text
Implement benchmarks/cfd phase 05 only. Complete the simulation CLI and add streaming VTI/PVD export of real completed fluid states, metrics, and resolved configuration. Respect cell-centered coordinates, post-collision field extraction, binary offsets, and snapshot ownership. Add a practical ParaView guide and interoperability tests where the viewer is available. Keep output synchronous and outside scheduler benchmark timing; do not build a renderer or frontend. Report the produced files and actual validation performed.
```
