# Phase 07 - External-solver validation

Dependencies: [05](05-visualization-and-simulation-cli.md), [06](06-stl-import-and-voxelization.md). Related components: [numerical model](NUMERICS.md), [benchmark eligibility](10-jmh-and-comparison-runner.md).

## Feature

An automated validation runner compares the serial CFD implementation with OpenLB on matched three-dimensional cases. Its reports expose numerical agreement independently of scheduler performance.

## Reference execution

The initial reference target is the published OpenLB 1.9.0 release. The reference installation and build configuration identify the exact source revision, compiler settings, precision, and selected dynamics. Reference computation runs in a separate process using OpenLB's own collision, streaming, forcing, and boundary implementations.

`validation/openlb` contains case configuration and field-export adapters. The adapter translates physical inputs and samples reference fields; numerical updates remain owned by OpenLB. The Java solver uses its own kernel. The two paths share case data and an exchange format.

The runner prepares paired cases, executes both solvers with bounded deadlines, normalizes snapshots, compares fields and observables, and writes JSON and Markdown reports. Process failures, incomplete output, and missing installations have explicit result states. Existing completed reference artifacts are reusable when their recorded case and reference identity match.

## Case correspondence

Each fixture describes geometry, effective wall locations, grid origin and spacing, dimensions, sample locations, density and pressure references, viscosity, forcing, initial conditions, inlet/outlet behavior, startup ramps, and physical sample times. Resolved reports from both executables expose the actual values used.

The method map identifies the stencil, equilibrium/collision model, relaxation parameter, force treatment, population-time convention, and boundary discretization on each side. Matching a configuration label alone is insufficient evidence of matching methods.

Two comparison modes describe the relationship:

| Mode | Comparison |
| --- | --- |
| Matched discretization | Corresponding cells and times with equivalent discrete operators, wall placement, and boundary treatment |
| Physical-case convergence | The same physical problem with documented discretization differences, evaluated at common physical locations and refinement levels |

A missing operator correspondence is reported as incompatible with matched-discretization comparison. Physical-case convergence has its own fixture, error budget, and refinement evidence. The selected mode is fixed in the case definition.

For equal grids, comparison uses directly corresponding fluid samples. Different grids use a declared sampling/interpolation method and coverage report. Pressure alignment uses the configured reference region or gauge; density conversion accounts for each solver's reference-density convention. Velocity extraction aligns the force correction and represented physical time.

## Reference cases

| Case | Main coverage |
| --- | --- |
| Resting fluid with stationary solids | Equilibrium, mass, wall symmetry, zero force |
| Periodic shear decay | 3D streaming, viscosity, transient time alignment |
| Body-force-driven channel | Forcing, effective wall position, velocity profile, mass |
| Inlet/outlet duct | Open boundaries, pressure drop, flow rate, boundary mass balance |
| Stationary obstacle flow | Spatial velocity/pressure fields, wake, force direction and magnitude |
| Small imported STL case | Resolved geometry, obstacle identity, field/export correspondence |

Matched voxel masks isolate solver differences in obstacle cases. Separate geometry comparisons evaluate independently resolved primitive/STL geometry, including cell classification and fluid-domain coverage. Analytical rest, shear, and channel results provide additional checks on both solvers' case setup.

Laminar transient comparisons use multiple aligned times. Steady cases report residuals and convergence before steady-field comparison. A fixture using different boundary discretizations includes refinement and boundary-region error summaries.

## Metrics and tolerances

Normalized snapshots contain coordinates, time, material/obstacle ID, velocity components, density, and gauge pressure. Scalar/vector observables include mass, inlet/outlet flux, pressure drop, obstacle forces, and drag coefficients with matched reference definitions.

Reports contain RMS and maximum absolute field errors, scaled errors, observable differences, compared sample counts, excluded regions, and the worst-error location. A scalar or vector field `q` uses:

```text
rmsError = sqrt(sum_j(norm(q_candidate[j] - q_reference[j])^2) / sampleCount)
maxError = max_j(norm(q_candidate[j] - q_reference[j]))
acceptanceLimit = absoluteTolerance + relativeTolerance * declaredReferenceScale
```

Each metric has its own limit. Reference scales are explicit physical quantities or declared reference-field norms with a defined zero-field fallback. Pressure, velocity, density, and force use separate units and tolerances.

Fixture tolerances are established from analytical checks, reference repeatability, discretization correspondence, and refinement studies before candidate scoring. Unresolved tolerances leave the fixture unverified. The report distinguishes measured errors from accepted limits.

## Report and benchmark integration

Case results are `PASSED`, `FAILED`, `UNAVAILABLE`, or `INCOMPATIBLE`, with unverified fixtures identified separately. Reports retain reference identity, candidate revision, configuration, sample times, method map, coverage, errors, and tolerance definitions.

A passing suite covers every physics feature used by an eligible benchmark case. Backend equivalence then checks FJP, static workers, and Euhedral against the externally checked serial implementation. Large scheduling variants can reuse that verified Java reference for field comparisons; the report identifies their external coverage separately from their same-kernel equality check.

## Verification

Comparator fixtures exercise zero reference fields, pressure-gauge offsets, unit conversion, coordinate and component permutations, time offsets, missing/duplicate samples, mask mismatch, NaNs, and absent reference output. Deliberate velocity/viscosity or force-sign perturbations produce failed comparisons. External integration checks execute a small real OpenLB suite and retain its actual output.

## Interface and references

```bash
benchmarks/cfd/build/install/euhedral-cfd/bin/euhedral-cfd validate --suite benchmarks/cfd/validation/suites/smoke.json --openlb-home "$OPENLB_HOME"
```

`OPENLB_HOME` identifies the pinned reference installation. The suite selects cases, reference build settings, comparison modes, limits, and artifact paths.

- [OpenLB project](https://www.openlb.net/)
- [OpenLB 1.9 release](https://www.openlb.net/news/openlb-release-1-9-available-for-download/)
- [OpenLB user guides](https://www.openlb.net/user-guide/)
