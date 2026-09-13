# Phase 05 - Visualization and simulation workflow

Dependencies: [04](04-open-boundaries-and-forces.md). Field definitions: [NUMERICS.md](NUMERICS.md).

## Feature

The simulation application exports three-dimensional flow fields as a ParaView time series. The command interface selects a backend, physical duration or step count, output location, export cadence, and validated configuration overrides.

## Field output

Completed states produce velocity, density, gauge pressure, and solid/obstacle labels. Velocity has three components. Cell-centered samples use X-fastest ordering and physical origin/spacing. An `nx*ny*nz` domain has VTI ImageData point extents `0 nx 0 ny 0 nz` and CellData arrays.

The VTI writer streams uncompressed appended data with explicit byte order and UInt64 headers and offsets. Small diagnostic fixtures also support ASCII output. A PVD collection associates completed frame files with physical time or explicitly labeled lattice time.

Export occurs synchronously at a successful timestep boundary. Stable population buffers supply the snapshot, and completed temporary files become visible before their PVD entries. Each run has a distinct output location. Interrupted runs retain completed frames and a status describing the interruption.

## Run data and visualization

Resolved configuration accompanies a metrics CSV containing step/time, numerical diagnostics, mass and flux, obstacle forces, simulation time, and export time. Field extraction follows the post-collision and half-force conventions of the numerical model. Output cost is reported separately from numerical execution cost.

`VISUALIZATION.md` describes the ParaView workflow: opening a PVD collection, displaying obstacle masks, inspecting slices, and generating streamlines. It covers Cell Data to Point Data conversion and the distinction between instantaneous streamlines and time-dependent particle paths.

The same coordinates and completed-state fields support the [external-solver comparison](07-external-solver-validation.md).

## Verification

A known non-cubic field with distinct vector components exercises array order, cell-center positions, physical units, and labels. Writer tests cover block lengths, offsets, large-offset arithmetic, PVD times, and interrupted publication. An installed VTK/ParaView reader supplies a separate interoperability check through a small-file round trip.

## Interface

```bash
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/duct-obstacle.json --backend serial --output benchmarks/cfd/build/runs/duct-demo
```
