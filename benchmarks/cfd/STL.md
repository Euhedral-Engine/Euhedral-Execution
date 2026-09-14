# STL obstacles and voxelization

Phase 6 imports watertight CAD-exported STL surfaces as stationary solids. Mesh preparation runs
before population initialization. The solver, range frames, bounce-back boundaries, force reduction,
and field exporter consume the existing integer obstacle mask; they retain no triangle data.

Build and inspect the self-authored box fixture:

```bash
mise exec -- gradle :benchmarks:cfd:build :benchmarks:cfd:installDist
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd inspect \
  --config benchmarks/cfd/scenes/stl-obstacle.json
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd inspect \
  --config benchmarks/cfd/scenes/stl-obstacle.json --voxelize
mise exec -- benchmarks/cfd/build/bin/euhedral-cfd simulate \
  --config benchmarks/cfd/scenes/stl-obstacle.json --steps 100 --export-every 10 \
  --output benchmarks/cfd/build/runs/stl-demo
```

Ordinary `inspect` streams each file to validate its syntax, coordinates, transform, triangle count,
bounds, and preprocessing estimate. It does not allocate triangle storage, a voxel mask, populations,
or workers. `--voxelize` explicitly adds welding, surface topology/intersection checks, voxelization,
and fluid connectivity analysis. It starts Euhedral workers for the parallel geometry stages and
closes its lattice on exit, without allocating populations or writing files. Simulation performs
those detailed checks using its existing lattice.

Open the printed run directory's `flow.pvd` using [VISUALIZATION.md](VISUALIZATION.md). The example
uses a 12-triangle unit box authored in this repository, with zero normal hints and explicit outward
vertex winding. It is not a third-party CAD asset or external solver reference.

## Configuration

Add meshes to `geometry.meshes` alongside boxes, spheres, and cylinders:

```json
"meshes": [
  {
    "id": 7,
    "name": "housing",
    "file": "meshes/housing.stl",
    "units": "MILLIMETERS",
    "scale": { "x": 1, "y": 1, "z": 1 },
    "rotationDegrees": { "x": 0, "y": 0, "z": 90 },
    "translation": { "x": 0.02, "y": 0.03, "z": 0.01 }
  }
]
```

This example requires physical simulation parameters. Required fields are positive integer `id`,
nonblank `file`, and `units`. IDs are unique across all primitive and mesh obstacles. `name` defaults
to `mesh-ID`; mesh names must be nonblank and unique. File paths resolve relative to the declaring
configuration file. Saved run configurations contain absolute mesh and output paths so replay does
not accidentally resolve assets relative to the run directory.

| Setting | Meaning |
| --- | --- |
| `units` | `LATTICE`, `METERS`, `MILLIMETERS`, `CENTIMETERS`, or `INCHES` |
| `scale` | Positive component scale; default `(1,1,1)` |
| `rotationDegrees` | Right-handed rotations about X, then Y, then Z; default zero |
| `translation` | Translation after rotation, in simulation coordinate units; default zero |
| `weldTolerance` | Optional vertex-welding distance in simulation coordinate units |

Simulation coordinates are meters in physical mode and lattice lengths otherwise. `LATTICE` mesh
coordinates convert through the configured voxel width in physical mode. SI mesh units require
physical parameters. Unit conversion and component scaling happen first, followed by rotation about
the origin and translation. Reflections, zero scale, overflow, and transforms below usable numeric
precision are rejected. Meshes are clipped to the domain, not copied periodically; the resulting
mask repeats across periodic faces. An obstacle outside the domain can therefore have zero cells
and zero measured force.

## Reader and topology rules

Binary detection validates `84 + 50*triangleCount` against the file length, using the unsigned
little-endian 32-bit count. An exact match takes precedence even when the 80-byte header begins
with `solid`. Each facet has 12 finite float32 values and a two-byte attribute field; normal hints
and attribute/color bits do not determine geometry. See the
[STL binary format description](https://www.loc.gov/preservation/digital/formats/fdd/fdd000505.shtml).

Other files must parse as a complete ASCII `solid ... endsolid` document with facet/normal,
outer-loop, exactly three vertices, end-loop, and end-facet delimiters. Decimal coordinates may
use scientific notation; non-finite values, trailing documents/data, malformed tokens, empty files,
and mismatched nonempty solid names fail. Tokens are limited to 128 bytes and names to 4096 bytes.
The reader streams through a 64 KiB buffer rather than retaining the file or its lines. A binary
count beyond the preprocessing allowance fails before triangle allocation. Each accepted file has
a SHA-256 fingerprint; loading verifies the fingerprint again to reject changes after inspection.

Vertices weld to the first previously encountered representative within the configured distance
on every coordinate (the maximum-component norm), searching the containing spatial bucket and its
26 neighbors. Representatives retain their original coordinates. The default tolerance is
`voxelWidth * 1e-8`; an explicit tolerance must be positive and no larger than `voxelWidth * 0.001`.
Coordinate precision must leave at least 16 floating-point ulps within that tolerance. Large offsets
or extremely small scales may require translating the model closer to the origin. Welding is
intended for small export cracks, not repair of missing faces or coarse CAD inaccuracies.

Detailed validation rejects:

- Degenerate triangles or collapsed edges after welding.
- Open edges, edges incident on more than two triangles, and inconsistent adjacent winding.
- Nonmanifold vertices with disconnected incident triangle fans.
- Duplicate triangles, detected self-intersections, and ambiguous contact between otherwise
  separate surfaces.
- Zero/invalid shell volumes and nested shells with inconsistent winding.

Each disconnected surface component is a shell. Disjoint outer shells may have either winding,
but a nested shell must reverse its immediate parent's winding. Interior classification uses
odd/even containment across all shells: an outer shell is solid, the next shell defines a fluid
cavity, and another nested shell becomes solid again. Shells that cross or touch are rejected;
use separate obstacle entries when intentional solid overlap is required. Across entries and
primitives, the lowest positive ID wins per cell. Domain wall labels retain their existing priority.

Triangle-pair checks use an AABB tree and separating axes, including coplanar separation axes.
Legal shared edges and vertices are excluded by a small inward displacement bounded by the welding
tolerance before testing incident faces. This is tolerance-based detection, not a general CAD repair
or exact-arithmetic proof for features below that tolerance. Errors identify the file/name and
triangle or edge involved. Correct the source mesh rather than relying on ambiguous contacts.

## Euhedral preprocessing

`GeometryRangeFrame` specializes `CfdFrame` for two kinds of work: independent triangle-intersection
checks and destination-owned voxel ranges. Frames retain replaceable primitive bounds and private
triangle-pair scratch. The owner reuses a bounded window of at most 256 frames across batches and
meshes; there is no range record, pipeline payload, or task allocation per voxel/brick. Exact
orientation fallback may allocate only when floating-point cancellation needs exact arithmetic.

`GeometryWork` submits these frames to ordinary `QueueIngestSink` sources. It creates no executor
or worker threads. Changing routing hashes permits parallel placement; submission across sources
is plain round-robin. `execution.geometrySources` selects 1-64 sources; omission uses one per
active worker up to 64. Set it to 1 for a single-source comparison. `execution.brick` sets voxel
range dimensions; partial edge bricks are included. Intersection checks use ranges of 64 triangles.

Parsing, welding, index construction, shell nesting, primitive/wall composition, and component
labeling remain driver-owned setup. Each mesh's parallel intersection stage must succeed before
shell nesting and painting start. All painting ranges must succeed before the next mesh can
modify overlapping cells. The owner acquires every terminal result before reducing integer counts,
applying primitives/walls, or exposing the completed mask. Empty queues are not completion barriers.

`execution.geometryDeadlineMillis` bounds each parallel stage, including dispatch and queue waits;
it defaults to 300000 ms. It does not time serial parsing/index construction or component labeling.
An error, interruption, or deadline cancels pending work and waits up to five seconds for terminal
acknowledgements before detaching sources. A failed private mask is never published or reused.
Library callers pass their lattice to `GeometryMask.resolve(configuration, lattice)` for STL work;
the caller retains ownership of that lattice.

## Voxel approximation and fluid passages

A balanced triangle AABB tree accelerates both triangle/cell overlap and point-in-solid queries.
A cell becomes solid if its closed cube intersects any triangle, with the welding tolerance added
as a conservative margin, or if its center lies inside the surface. Triangle/cube intersection uses
the box axes, triangle normal, and edge-cross-axis separating tests. This follows the separating-axis
construction described by
[Akenine-Moller](https://doi.org/10.1145/1198555.1198747).

The interior test casts a deterministic +X ray. A half-open top-left rule in the YZ projection
assigns shared projected edges and vertices to one incident triangle. Parallel faces contribute no
crossings; near-zero orientation determinants use exact decimal arithmetic on the represented
binary coordinates. Triangle order does not change exact shared-edge ownership.

Conservative marking includes cells on both sides of a surface lying exactly on a voxel face. It
can thicken solids and close small gaps; it is deliberately different from the center-only analytic
primitive mask within this surface band. Away from that band an imported box agrees with the
analytic box. Bounce-back still locates the effective wall halfway between solid and fluid centers.
Refine the voxel width when surface thickening changes the passage of interest.

After composing meshes, primitives, and domain walls, analysis visits the actual six-axis and
18-moving-direction D3Q19 fluid graphs, including periodic wraparound. It reports:

- Axis and D3Q19 component counts and undirected link counts.
- Diagonal links whose two intermediate axis neighbors are blocked (`cornerOnlyLinks`). These
  flag possible corner-dependent passages, not a certified physical gap width.
- Fluid cells blocked in both directions on at least one axis (`oneCellGapCells`), identifying
  one-cell channels and other under-resolved regions.
- Inlet/outlet fluid-cell counts, D3Q19 components joining both faces, and fluid cells in components
  reaching neither open face.

Disconnected cavities are retained and reported. One-cell and corner-dependent paths are reported
for review; they do not alter the D3Q19 kernel. A case with open boundaries fails if no D3Q19 fluid
component connects inlet to outlet. Existing rules also reject any obstacle touching an open-face
plane and any geometry leaving no fluid. Connectivity does not establish adequate hydrodynamic
resolution or replace a grid-refinement study.

## Artifacts, memory, and checks

`resolved.json` includes mesh transforms, paths, SHA-256 digests, formats, triangle counts, bounds,
and preprocessing estimates. After successful initialization, `geometry.json` adds welded vertex,
shell and cavity counts, surface/interior cell counts within the clipped domain before overlap
priority, voxel width, and the combined-mask connectivity report. Mesh names and IDs connect this
metadata to the existing force CSV and `obstacle_id` VTI array. Detailed inspection prints the same
report. Mesh indexes are shared read-only by preprocessing frames and do not survive into a numerical frame.

Preflight reserves 2048 bytes per triangle for the largest mesh processed at once, eight bytes per
cell for connectivity labels and BFS queue, 1024 bytes per mesh for retained metadata, and 1 MiB for the bounded frame/source window. These
allowances are added to the existing population, mask, force, and output estimates; they are
conservative estimates, not retained-heap measurements. Triangles, welded topology, and the AABB
tree are allocated after preflight; each mesh is processed separately. Connectivity scratch is
reused between the axis and D3Q19 traversals. Mesh coordinate/node arrays also obey Java array-size
limits. Preparation observes thread interruption and fails without advancing a timestep.

Tests generate equivalent binary/ASCII boxes, malformed and oversized files, cracked and invalid
surfaces, nested shells, and narrow passages at two resolutions. They check conservative box bands,
exact ray edges/vertices, winding, overlap IDs, replay paths, blocked inlet/outlet flow, connectivity,
and an oblique thin wall that prevents axis and diagonal leakage. Frame tests cover terminal publication, cancellation, deadlines while queued, scratch/bounds reuse,
worker failures, and identical masks with one/two workers and one/multiple sources. The STL scene also runs through
inspection, the serial solver, obstacle forces, and field export. External solver correspondence
and accuracy comparisons remain [Phase 07](07-external-solver-validation.md).
