# External solver validation

`validate` runs serial Java CFD and OpenLB in separate, bounded processes. It compares completed
physical fields and observables, writes JSON and Markdown reports, and retains both executables'
outputs. It does not run scheduling benchmarks or establish backend equivalence.

## Build and run

The reference adapter uses the published [OpenLB 1.9.0 release](https://www.openlb.net/news/openlb-release-1-9-available-for-download/).
Download its archive and build a new reference installation with Python 3.12+, GCC 12+ and Make:

```bash
curl -fL 'https://zenodo.org/records/17899765/files/olb-1.9r0.tgz?download=1' -o /tmp/olb-1.9r0.tgz
python3 benchmarks/cfd/validation/openlb/build.py \
  --archive /tmp/olb-1.9r0.tgz --destination /tmp/euhedral-openlb
export OPENLB_HOME=/tmp/euhedral-openlb
mise exec -- gradle :benchmarks:cfd:installDist
mise exec -- benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd validate \
  --suite benchmarks/cfd/validation/suites/smoke.json --openlb-home "$OPENLB_HOME"
```

The destination must not exist. Setup verifies archive SHA-256
`e4d2421b50643482036917c4416145f7d6b495492f3afac64e048212361c8b3e`, extracts that source, and
builds with double precision, `-O2`, C++20, CPU_SISD, and parallel mode OFF. No fast-math or native
architecture flags are enabled. `identity.json` records the archive, compiler, configuration,
adapter, and executable hashes; `build.log` retains the actual compiler commands. OpenLB's source
and license remain in the installation. Java distributions bundle the adapter, setup script and
fixtures, not an OpenLB executable. An adapter change requires rebuilding the reference installation.

`OPENLB_HOME` points to this adapter installation, containing `cfd-openlb` and `identity.json`.
A bare OpenLB source checkout is not sufficient. The runner checks the executable hash and that
the adapter matches the source bundled in its JAR. It never downloads or compiles implicitly.

| Suite | Purpose |
| --- | --- |
| [smoke](validation/suites/smoke.json) | Rest, shear, forced channel, stationary sphere, and imported STL on matched masks |
| [refinement](validation/suites/refinement.json) | Coarse/fine shear and channel analytical checks at common physical times |
| [coverage](validation/suites/coverage.json) | Smoke plus inlet/outlet duct; duct remains incompatible with matched discretization |
| [convergence](validation/suites/convergence.json) | Different channel grids with trilinear sampling; measured errors, unverified convergence budget |

Suite paths resolve relative to the suite file. Each run gets a unique directory beneath the
configured output directory. Exit codes are 0 for a fully passing, verified suite, 2 for invalid
CLI/configuration input, and 4 for any failed, unavailable, incompatible or unverified case.
`--help` describes the launcher options. `--openlb-home` overrides `OPENLB_HOME`.

## Numerical correspondence

Both paths use D3Q19, second-order BGK, double precision and Guo forcing. OpenLB uses its own
`ForcedBGKdynamics`, streaming, equilibrium/source initialization, and `BouzidiPostProcessor` with
link distance 0.5. The adapter does not implement collision or streaming equations.

The exchanged state is post-collision `g(t)`. OpenLB normally schedules collision then streaming;
the adapter advances the corresponding stream/boundary/collision cycle using OpenLB operations.
After OpenLB's setup postprocessors, it restores equilibrium plus half the Guo source through
OpenLB's initialization routines. Velocity sampling uses raw post-collision moments minus half the
acceleration, matching the represented time. Native momentum-exchange force sampling occurs after
streaming/boundary processing and before collision. The reference never imports Java populations.

`candidate/resolved.json`, each solver's `method.json`, and the reference `case.txt` retain actual
inputs and methods. The report records coordinates, spacing, physical sample times, units,
initialization, tau, forcing, geometry, open-face parameters, source revision and loaded Java artifact
hash. The artifact hash covers uncommitted compiled changes; a source revision alone is insufficient.

The Java inlet/outlet uses Hecht-Harting reconstruction; this adapter uses OpenLB's native Zou-He
velocity/pressure dynamics. Their finite-grid results differ. `coverage.json` runs and retains the
duct results as `INCOMPATIBLE`, with no external eligibility for open-boundary features. The runner
does not switch comparison modes automatically or loosen field limits to admit it.

All included simulations are transient, with multiple aligned times. They make no steady-state
convergence claim. Analytical checks independently check rest, Fourier shear decay, and the transient
parallel-plate Poiseuille series on both outputs. Their continuum setup limits are distinct from
candidate/reference acceptance limits. The refinement suite checks the same physical time interval
with half the voxel width and one quarter of the timestep.

## Sampling, geometry and error budgets

Normalized TSV snapshots contain physical `x,y,z,time,material,obstacle,ux,uy,uz,rho,pressure`.
Coordinates are cell centers from origin zero; lattice configurations use identity units. Density
is absolute density, and gauge pressure is `(rho-rhoReference)*cs^2` in the declared physical units.
The reader rejects invalid/nonfinite samples, coordinate duplicates, wrong headers, missing cells,
material/ID disagreement, and off-grid times/locations. Row order is immaterial; component order is
explicit in the header.

Matched cases use `DIRECT` sampling. A `PHYSICAL_CASE_CONVERGENCE` fixture may select a separate
`referenceConfig`, `TRILINEAR` sampling and `minimumCoverage` (default 1). Sample steps are converted
through physical time and must land on integer reference steps. Interpolation requires eight fluid
neighbors; it never extrapolates or interpolates through solids. Reports count covered/excluded fluid
samples, retain full-domain observable comparisons separately, and enforce the declared coverage.
`pressureGauge` selects absolute pressure comparison or removal of the common-fluid mean offset.
The latter aligns a pressure gauge; it does not remove spatial pressure errors.

For obstacles, the solver comparison uses a matched voxel mask. `geometry.tsv` separately compares
that mask with OpenLB's independent primitive/STL indicators. Java's STL voxelizer conservatively
marks surface-intersecting cells, while the native reference reader classifies cell centers. The
small box fixture therefore has 56 classification differences (64 versus 8 solid cells). This is
reported independently; passing the shared-mask flow test does not claim equivalent STL voxelizers.
Wall layers are shared in this geometry comparison. IDs, fluid coverage and all classifications
remain available for inspection.

Every field reports RMS/max absolute error, errors divided by an explicit nonzero scale, the worst
location, sample counts, and `absolute + relative * scale` acceptance limit. Velocity uses vector
norms. Density, pressure, mass, force, drag, face flux and pressure drop have their own scales/units.
Mass integrates density over cell volume. Face fluxes are macroscopic `rho*u_normal*area` estimates;
they are not interchangeable with Java's discrete boundary-update mass transfers. Force is force on
the obstacle; drag uses the declared density, speed, area and direction.

[roundoff-budget.json](validation/roundoff-budget.json) freezes a conservative floating-point budget
for the listed matched fixtures, not a fitted continuum error allowance. It binds configuration
bytes, mesh content, sample times, features, gauge and sampling. Its limits were specified before
candidate scoring; analytical checks, independent reference repeatability and the refinement suite
provide separate setup evidence. Each newly generated reference must reproduce identical output in
a second process. Changes to the qualifying inputs or missing/mismatched evidence leave tolerances
unverified. Convergence mode additionally requires at least two hashed `refinementEvidence` artifacts
and a separately qualified tolerance file; merely selecting the mode grants no eligibility.

## Reports and reuse

`report.json`, `report.md`, and per-case `result.json` distinguish `PASSED`, `FAILED`, `UNAVAILABLE`,
and `INCOMPATIBLE`. `verified=false` is independent of measured agreement. Process exit codes,
deadlines, logs and incomplete outputs remain visible; none becomes an empty successful comparison.
The parent terminates timed-out solvers. The Java child selects one allowed worker and a bounded
heap, so correctness validation does not allocate one worker per host CPU.

Reference cache keys include normalized case inputs, mesh identities, the full reference identity,
and its executable/adapter hashes. Only completed, repeated references with matching content
manifests are reused. Candidate runs always execute again. Cache contents never grant candidate
eligibility by themselves.

`verifiedFeatures` lists only features from passing, qualified cases. `externalNumericsEligible`
requires the entire selected suite to pass. `backendEquivalenceVerified` remains false: the later
backend checks must independently compare each Java backend against the externally checked serial
implementation. Neither a passing smoke suite nor a copied report qualifies arbitrary physics,
changed configurations, a different loaded numerical implementation, or scheduling performance.

## Verification

```bash
OPENLB_HOME=/tmp/euhedral-openlb mise exec -- gradle \
  :benchmarks:cfd:build :benchmarks:cfd:integrationTest :benchmarks:cfd:installDist
```

Without `OPENLB_HOME`, the external integration test is explicitly skipped; comparator and process
unit tests still run. External integration retains real snapshots/reports under
`benchmarks/cfd/build/openlb-integration-*`, including cache reuse. VTK integration has its separate
`CFD_VTK_PYTHON` environment setting described in [VISUALIZATION.md](VISUALIZATION.md).

Reference sources: [OpenLB release archive](https://zenodo.org/records/17899765),
[OpenLB user guides](https://www.openlb.net/user-guide/), and the pinned installation's
`src/dynamics/forcing.h`, `src/boundary/setBouzidiBoundary.h`, `src/boundary/zouHeDynamics.h`,
`src/core/superLattice.hh`, and `src/functors/lattice/latticePhysBoundaryForce3D.hh`.
