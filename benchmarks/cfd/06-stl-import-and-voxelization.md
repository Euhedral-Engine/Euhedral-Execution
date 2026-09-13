# Phase 06 - STL import and voxelization

Dependencies: [05](05-visualization-and-simulation-cli.md). Geometry and storage model: [NUMERICS.md](NUMERICS.md).

## Feature

CAD-exported STL geometry defines stationary obstacles and resolved fluid passages in the three-dimensional simulation domain.

## Components

Bounded-memory binary and ASCII STL readers validate file structure, triangle counts, lengths, and coordinates. Binary detection uses validated structure and length, including files whose headers begin with `solid`.

Geometry configuration specifies mesh units, scale, rotation, and translation. Transform validation detects singular or non-finite values and memory-budget violations. Inspection reports bounds, triangle count, transform, and preprocessing estimates.

Topology analysis uses a documented vertex-welding tolerance and identifies degenerate triangles, open/nonmanifold edges, inconsistent shells, and detected ambiguous self-intersections. Geometry errors carry mesh and location diagnostics.

A spatial index accelerates triangle/voxel intersection and deterministic point-in-solid classification. Surface intersection is conservative; interior classification fills solid volumes. Shared-edge/vertex rules, nested shells, cavities, named obstacle IDs, and overlap semantics define the mask consistently.

Resolved topology checks cover D3Q19 axis and diagonal links, disconnected fluid regions, blocked inlet/outlet passages, and under-resolved gaps. Voxelization metadata describes the resulting geometric approximation and its resolution.

Imported masks use the existing kernel, boundary handling, force attribution, and field exporter. Preprocessing occurs before timestep execution. A small self-authored watertight fixture and `scenes/stl-obstacle.json` provide an end-to-end case. `inspect` offers explicit detailed voxelization analysis.

## Verification

Coverage includes equivalent ASCII/binary files, misleading headers, truncation, oversized counts, malformed coordinates, open/nonmanifold meshes, transforms, and exact ray/triangle edge intersections. A generated STL box agrees with the analytic box away from its conservative surface band.

Nested/cavity fixtures and narrow passages at two resolutions exercise geometry semantics and diagonal leakage. The imported scene feeds both the serial simulation and the [external-validation workflow](07-external-solver-validation.md).

## Interface

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/stl-obstacle.json --backend serial --output benchmarks/cfd/build/runs/stl-demo
```
