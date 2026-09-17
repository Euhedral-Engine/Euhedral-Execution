# Euhedral Execution repository guide

This file governs work across the repository. Read it before making changes, then read the
`AGENTS.md` only for the module or modules relevant to the task. Do not read unrelated module
guides. A relevant module guide may add narrower instructions for its own subtree; it does not
override repository-wide safety, verification, or ownership rules here. An empty module guide means
that only this root guide currently applies.

Treat the checked-out implementation, build files, and nearest tests as authoritative. Documentation
is supporting context and can lag the code. In particular, verify architecture claims against the
current branch before carrying them into code, tests, or other documentation.

## Repository map

The authoritative Gradle project list is [settings.gradle.kts](settings.gradle.kts). The repository
also contains an included convention-plugin build and a first-class Python package.

| Module                             | Role                                                                  | Module guide                                                                             |
|------------------------------------|-----------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| `build-logic`                      | Shared Gradle conventions and test infrastructure                     | [build-logic/AGENTS.md](build-logic/AGENTS.md)                                           |
| `euhedral-hashing`                 | Hashing and mixing primitives                                         | [euhedral-hashing/AGENTS.md](euhedral-hashing/AGENTS.md)                                 |
| `euhedral-data-structures`         | Concurrent queues, atomics, and low-level data structures             | [euhedral-data-structures/AGENTS.md](euhedral-data-structures/AGENTS.md)                 |
| `euhedral-hardware-utils`          | Hardware topology, affinity, sampling, pressure, JNI, and native code | [euhedral-hardware-utils/AGENTS.md](euhedral-hardware-utils/AGENTS.md)                   |
| `euhedral-core`                    | Execution engine, control plane, routing, frames, ingest, and metrics | [euhedral-core/AGENTS.md](euhedral-core/AGENTS.md)                                       |
| `euhedral-reactor-core`            | Reactor integration                                                   | [euhedral-reactor-core/AGENTS.md](euhedral-reactor-core/AGENTS.md)                       |
| `euhedral-spring-core`             | Spring, Kafka, and gRPC integration                                   | [euhedral-spring-core/AGENTS.md](euhedral-spring-core/AGENTS.md)                         |
| `benchmarks`                       | JMH benchmarks and calibration harnesses                              | [benchmarks/AGENTS.md](benchmarks/AGENTS.md)                                             |
| `benchmarks:cfd`                   | CFD application, validation, and benchmarks                           | [benchmarks/cfd/AGENTS.md](benchmarks/cfd/AGENTS.md)                                     |
| `python/pareto-weight-calibration` | Offline fitting, policy evaluation, and model export tooling          | [python/pareto-weight-calibration/AGENTS.md](python/pareto-weight-calibration/AGENTS.md) |

The root project owns aggregation and repository-wide reporting. `docs`, `scripts`, and root-level
configuration are root-owned unless a task clearly belongs to one of the modules above. Native Zig
and JNI sources are owned by `euhedral-hardware-utils`, not by a separate module.

The intended dependency direction is from low-level libraries into `euhedral-core`, then into
integration modules and applications. Do not introduce dependencies from hashing, data structures,
or hardware utilities back into core or higher-level integrations. Confirm the exact current graph
in module build files before changing dependencies.

## Start every task with scope

1. Run `git status --short --untracked-files=all` and account for the existing state. Preserve
   unrelated edits, staged changes, ignored data, generated outputs, build products, and local
   experiment results.
2. Identify the owning module or modules. Read this file and only those modules' `AGENTS.md` files,
   build files, active `module-info.java` files, implementation, callers, and nearest behavioral
   tests.
3. Define the smallest change that satisfies the request. Do not widen a local task into cleanup,
   regeneration, benchmark execution, dependency upgrades, or production-policy changes.
4. Never commit, push, publish, release, delete user data, rewrite history, or clean the repository
   unless the user explicitly requests that action.
5. Do not use `git clean`, destructive reset commands, or incidental `gradle clean`. Ignored and
   generated-looking paths can contain expensive or irreplaceable local evidence.

When a change crosses module boundaries, validate the contract at both sides. Updating a dependency
without running its own tests does not establish that the dependency still behaves correctly.

## Authoritative repository references

Use these files instead of duplicating facts that can drift:

- [README.md](README.md) for the public project overview.
- [BUILD.md](BUILD.md) for build and native prerequisites.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for architecture context, subject to verification
  against current code and tests.
- [mise.toml](mise.toml) for tool versions and native-build environment variables.
- [settings.gradle.kts](settings.gradle.kts) for Gradle project membership and repository policy.
- [gradle/libs.versions.toml](gradle/libs.versions.toml) for dependency versions.
- [build-logic/src/main/kotlin/buildlogic.java-conventions.gradle.kts](build-logic/src/main/kotlin/buildlogic.java-conventions.gradle.kts)
  for shared Java, test, formatting, coverage, publication, and signing conventions.
- [.github/workflows/build.yaml](.github/workflows/build.yaml) and the other files under
  `.github/workflows` for CI behavior. Inspect the relevant workflow rather than assuming all jobs
  run the same tasks.

Search current source before relying on a class, task, benchmark selector, script, configuration
field, or test named in documentation. Do not restore removed behavior merely because an old guide
or script still mentions it.

## Toolchain and command entry points

Use the checked-in Mise environment, not ambient Java, Gradle, Zig, or signing tools:

```bash
mise install
mise exec -- java -version
mise exec -- gradle --version
mise exec -- gradle <tasks>
```

The repository does not track a complete executable Gradle wrapper, so use `mise exec -- gradle`,
not `./gradlew`. The Java toolchain is selected centrally, while some published modules target an
older Java release; read the owning module's build file before changing language or API usage.

Gradle configuration cache, build cache, and parallel execution are enabled. Tests also use shared
parallel-execution conventions. Tests that mutate process-wide state, native state, affinity,
singletons, system properties, or external services must use the repository's established
isolation and teardown patterns.

## Build, format, and test

Prefer the narrowest meaningful checks while iterating:

```bash
mise exec -- gradle :<module>:test :<module>:spotlessCheck
```

Add tests for directly affected dependencies and consumers. A module test task may compile upstream
projects without executing their tests. Use `--tests` for focused iteration, but run the owning
module's full test task before handoff when practical.

For repository-wide or cross-module Java changes, run:

```bash
mise exec -- gradle build integrationTest
```

`test` excludes tests tagged `integration`; `integrationTest` runs the integration-tagged tests.
Some integration tests depend on host hardware, external software, environment variables, or enough
available physical cores and can skip. Report skips, `NO-SOURCE`, filtered selections, and
`UP-TO-DATE` tasks accurately; a successful Gradle invocation is not necessarily executed
behavioral coverage.

Spotless is the Java formatter. Prefer `spotlessCheck`; use `spotlessApply` only for intended files
and inspect the resulting diff. The repository does not define one formatter or linter that covers
all Python, Kotlin, Gradle, Markdown, Zig, and workflow files, so follow surrounding style and use
the owning toolchain where configured.

For documentation-only changes, validate structure, paths, links, whitespace, and the final diff.
Do not run an expensive native build or test suite merely to claim validation unrelated to the edit.

## Native, generated, and tracked evidence

Generated source, JNI declarations, native binaries, catalogs, benchmark output, datasets, and
model exports have different owners. Before changing any of them, identify the source of truth and
the task that generates the artifact.

- Do not hand-edit build outputs or generated native/JNI artifacts.
- Do not assume every generated-looking tracked file is disposable. Tracked datasets and generated
  runtime models can be canonical evidence with review requirements.
- Do not regenerate checked-in code from an undocumented or guessed tool version.
- Keep generated output and caches out of unrelated diffs.
- Separate source failures from missing SDKs, inspection tools, signing tools, external runtimes,
  platform capabilities, and host-specific native limitations.

Changes to generation inputs must include verification of the generated consumer where the tooling
is available. If exact regeneration is unavailable, stop and report the missing prerequisite rather
than synthesizing output.

## Python work

The Python calibration package is independent of Gradle's Java test lifecycle. Work from
`python/pareto-weight-calibration`, use Python 3.12 or newer as required by its `pyproject.toml`,
and
use that package's declared development dependencies. Run focused pytest coverage first, then the
appropriate package test set.

Do not assume Java CI runs Python tests. Some Python commands train models, launch Gradle/JMH work,
write experiment databases, or export tracked Java/resources. A dry run, input validation, fitting,
benchmark execution, artifact export, and promotion into production are separate scopes and require
separate evidence.

## Performance, concurrency, and hardware-sensitive code

Euhedral contains latency-sensitive concurrent paths. Preserve established ownership, queue
topology, memory-ordering, allocation, and affinity decisions unless the task explicitly changes
their contract.

- Explain the publication and happens-before argument when changing atomics, VarHandles, locking,
  or weaker/stronger access modes.
- Match SPSC, SPMC, MPSC, or MPMC structures to the actual producer/consumer topology.
- Avoid new allocation, streams, blocking I/O, string formatting, or chatty logging in hot paths.
- Give every spin, wait, drain, and shutdown path a progress, timeout, cancellation, or termination
  condition.
- Prefer deterministic synchronization to sleeps in tests.
- Treat managed CPU identity, requested affinity, and physical placement as distinct facts.
- Close executors, lattices, monitors, channels, containers, and external resources at their
  ownership boundary.

Functional tests and ad hoc wall-clock timings do not establish throughput or latency improvements.
Make performance claims only from an explicitly authorized, reproducible benchmark with retained
configuration and results.

## Experiments and benchmark safety

Compilation, configuration validation, benchmark execution, result comparison, model fitting,
artifact export, and production-default changes are distinct actions. Authorization for one does
not authorize the others.

Do not launch JMH campaigns, CFD benchmarks, calibration runs, model training, policy searches, or
large-memory profiles unless the user explicitly requests execution. Preserve all local experiment
output, including ignored files. Never adjust production defaults solely because a local experiment
appears favorable unless that promotion is part of the requested scope and the evidence is reviewed.

Benchmark and experiment code can normally be compiled and unit-tested without running a campaign.
Use the module guide and launcher help from the checked-out branch to determine valid selectors and
options; old READMEs and scripts can lag current launchers.

## Editing conventions

- Follow local naming, package, and file-layout conventions.
- Keep diffs focused; do not reformat or rename unrelated code.
- Preserve JSpecify annotations and validate public constructor, record, and configuration
  invariants at boundaries.
- Use SLF4J placeholders and pass a throwable last rather than eagerly formatting log messages.
- Update active JPMS exports/requires when changing public package boundaries. A disabled descriptor
  is not part of compilation.
- Use comments for ownership, ordering, memory semantics, external constraints, and non-obvious
  performance decisions. Do not narrate straightforward code.
- Add regression tests at the nearest layer that can prove the changed contract, including failure,
  cancellation, reuse, boundary, or concurrency behavior where relevant.

## Handoff checklist

Before returning work:

1. Re-read every changed file and inspect the complete intended diff.
2. Search changed areas for stale names, broken links, outdated callers, and accidental generated
   artifacts.
3. Run the focused checks required by each owning module, then broader checks justified by the
   impact. Record what actually executed and what skipped.
4. Run:

   ```bash
   git diff --check
   git status --short --untracked-files=all
   ```

5. Account for every changed or untracked path and confirm unrelated state is preserved.
6. Report the files changed, the exact validation performed, failures or skips, and any scope that
   remains unverified. Do not label unrun tests, benchmarks, platform checks, or regeneration as
   complete.
