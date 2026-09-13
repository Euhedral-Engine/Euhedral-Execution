# Phase 06 - STL import and voxelization

Prerequisite: 05. Read the [master plan](README.md), geometry contracts from phase 03, and the memory limits in [NUMERICS.md](NUMERICS.md).

## Deliverable

A user can export a stationary object from CAD as STL, place it in a 3D flow domain, and simulate flow around or through its resolved fluid passages.

## Changes

- Implement bounded-memory readers for binary and ASCII STL. Detect binary form from validated structure/file length, not only a `solid` prefix, which binary headers may contain. Validate triangle counts and lengths with checked arithmetic.
- Require explicit mesh length units and transform. STL does not supply reliable units. Apply scale, rotation, and translation deterministically; reject singular/non-finite transforms and out-of-budget geometry. Do not auto-fit a model in a way that changes its physical scale.
- Validate finite coordinates, degenerate triangles, and closed/manifold topology with a documented vertex-welding tolerance. Reject detected holes, nonmanifold edges, inconsistent shells, or ambiguous self-intersections with a diagnostic; do not pretend every CAD export is usable or silently repair geometry.
- Build a bounding-volume hierarchy or comparable spatial index once. Implement robust point-in-solid classification with a deterministic shared-edge/vertex rule, plus conservative triangle/voxel surface intersection. Do not classify only triangle surfaces and leave solid interiors as fluid.
- Document shell/interior semantics, including nested surfaces and cavities. Preserve explicitly modeled fluid passages when resolved; ambiguous overlapping shells are rejected rather than guessed. Support multiple named obstacles with stable force attribution and a stated overlap policy.
- Check the resolved voxel topology against D3Q19's axis and face-diagonal links. Report under-resolved gaps, blocked inlet/outlet passages, and disconnected fluid regions. Do not claim a successful mesh check guarantees every sub-voxel feature survives voxelization.
- Route imported geometry through the same mask, kernel, force accounting, and exporter as primitives. Voxelization is setup work, never part of a timed timestep.
- Add a tiny self-authored watertight fixture and `scenes/stl-obstacle.json`. Include the fixture's provenance. Do not download or commit a large third-party CAD collection.
- Extend `inspect` to report bounds, units/transform, triangle count, estimated preprocessing memory, and geometry validation. Heavy voxelization should require an explicit inspection option, not happen unexpectedly for a simple config check.

## Acceptance

Cover ASCII/binary equivalence, misleading binary headers, truncated files, oversized counts, bad coordinates, degenerate/open/nonmanifold meshes, transforms, and exact triangle-edge ray intersections. Compare a generated STL box against the analytic box mask away from the conservative surface band.

Test nested/cavity semantics and a narrow passage at two resolutions. A small imported object must run through the existing serial solver and open in the existing output pipeline. Mesh parsing alone is not completion.

```bash
mise exec -- gradle :benchmarks:cfd:test :benchmarks:cfd:spotlessCheck :benchmarks:cfd:installDist
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd simulate --config benchmarks/cfd/scenes/stl-obstacle.json --backend serial --output benchmarks/cfd/build/runs/stl-demo
```

Keep the default mesh small and explicitly label voxelized geometry as an approximation.

## Implementation prompt

```text
Implement benchmarks/cfd phase 06 only. Add bounded binary/ASCII STL loading, explicit units/transforms, topology validation, accelerated solid voxelization, and one small self-authored fixture. Preserve interior/cavity semantics and test diagonal stencil leakage and under-resolved passages. Connect imported masks to the existing simulator, forces, and VTI output. Do not add native CAD parsing, automatic mesh repair, or a geometry service. Run focused tests and one small end-to-end imported scene.
```
