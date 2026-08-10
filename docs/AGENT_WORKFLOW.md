# Staged Agent Workflow

Use this workflow for non-trivial changes so the implementation has a concise, reviewable design
to follow. Keep the artifacts proportional to the change. The plan at
`docs/plans/dynamic-fragment-control-policy.md` is the style reference: clear intent, bounded scope,
settled direction, and only the detail needed for a safe implementation.

## Intensity levels

Record an intensity level, not a model name. Use the lowest level that can safely preserve the
relevant contracts.

| Intensity | Use for |
|---|---|
| Maximum | Architectural decisions, concurrency, lifecycle, topology, mathematical precision, or broad cross-module design. |
| High | Coupled implementation with meaningful failure handling, memory semantics, or integration risk. |
| Medium | Bounded implementation, focused tests, verification, or cleanup with clear local behavior. |
| Low | Mechanical edits with no material design or compatibility decisions. |

Do not use a lower intensity to avoid resolving an architectural decision. Do not use a higher
intensity to add unnecessary process or detail.

## Work model

The normal artifacts are:

1. A plan that states intent and boundaries.
2. A blueprint that settles the implementation decisions the code cannot safely infer.
3. An implementation with focused tests and completion notes.

Use one plan and one blueprint by default. Split a blueprint only when two responsibilities are
genuinely independent or the implementation cannot reasonably hold the work together. Do not create
child blueprints, branches, review artifacts, or phase trees merely to satisfy the workflow.

## Toolchain and repository safety

Every Java command and Gradle build uses the versions in `mise.toml`; use `mise exec --` when
available. A restricted-environment fallback must record the substituted versions and its limits.

Before each action, run `git status --short`. Preserve pre-existing user changes, generated data,
benchmark output, and local runs. Do not commit, push, merge, delete user data, or rewrite unrelated
files unless explicitly authorized. Use `apply_patch` for source and documentation edits.

Read `AGENTS.md`, this workflow, relevant architecture documentation, module build files and module
descriptors, affected README files, and nearby tests before changing code. Do not edit production
code while planning or writing a blueprint.

## Pipeline

### 1. Plan

Intensity: maximum for material architecture; otherwise high or medium.

Explore the repository before asking questions. Resolve facts from code and tests. Ask only about
product intent or tradeoffs that cannot be discovered locally.

Create `docs/plans/<PLAN_NAME>.md` with:

- objective and success criteria;
- developer requirements, scope, and non-goals;
- current-state findings and affected components;
- the selected direction and important constraints;
- risks, compatibility boundaries, and the tests or benchmarks that matter; and
- a short work sequence naming the blueprint and implementation intensity.

Do not require exact model names, exhaustive prompt rankings, branch lineage, or an audit strategy
unless the change genuinely needs them.

### 2. Blueprint

Intensity: maximum for architectural or concurrency-heavy work; high for coupled integration; medium
for bounded work.

Create `docs/blueprints/<FEATURE>.md` unless the plan names another path. Make it implementation-ready
but concise. Specify only the decisions the implementer should not infer, including as applicable:

- selected architecture and ownership boundaries;
- public interfaces, data flow, invariants, and failure behavior;
- concurrency, memory ordering, lifecycle, compatibility, or migration rules;
- deterministic formats or algorithms where they affect observable behavior; and
- focused tests and acceptance criteria.

Use local code conventions for names and small mechanics. Do not enumerate every file, restate
unchanged behavior, invent detailed policy that the request does not require, or list alternatives
that do not change the decision. A blueprint is complete when an implementer can proceed without a
material architectural choice, not when it contains maximum detail.

If the blueprint discovers a material ambiguity, record the evidence and ask for a decision. If the
scope is still too large, split only the genuinely independent work and keep the parent summary
short.

### 3. Implementation

Intensity: use the level selected by the plan or blueprint.

Read `AGENTS.md`, the plan, the blueprint, and the named tests before editing. Implement only the
settled scope. Do not introduce architecture, broaden module ownership, or reopen decisions settled
by the blueprint. If a new material decision is required, stop and record the conflict in the
blueprint rather than guessing.

When creating a class or method, or changing a method signature, add an adjacent Markdown-style
`///` comment describing purpose and any non-obvious ownership, ordering, unit, or failure semantics.

Run the narrowest meaningful tests, then broader module or repository checks when the change warrants
them. Append concise completion notes to the blueprint: changed files, commands, results, acceptance
evidence, and environmental limits.

### 4. Verification and handoff

Verification is part of implementation, not a mandatory separate conformance phase. Check the
blueprint acceptance criteria, focused tests, `git diff --check`, stale names or references, and
`git status --short`.

Create a separate audit or conformance document only when the developer requests one, the plan
requires one, or the change has enough risk that the evidence cannot fit usefully in the blueprint
completion notes. Minor blueprint-settled corrections are allowed during verification; redesigns
return to the blueprint.

## Prompt templates

Planning:

> Use maximum intensity when the change involves architecture, concurrency, lifecycle, topology,
> mathematics, or multiple modules; otherwise use the appropriate lower intensity. Read the
> repository instructions and relevant code and tests. Explore first, resolve intent questions, and
> write `docs/plans/<PLAN_NAME>.md`. Do not modify production code. Keep the plan concise and
> decision-oriented.

Blueprint:

> Use the intensity named by the plan. Read the instructions, plan, relevant code, tests, and prior
> completion notes. Do not modify production code. Write `<BLUEPRINT_PATH>` as a concise,
> implementation-ready design. Settle only the material architecture, interfaces, invariants,
> failure behavior, memory/lifecycle rules, and acceptance tests. Infer local naming and mechanics
> from the repository. Split the work only if it is genuinely independent.

Implementation:

> Use the intensity named by the completed blueprint. Read the instructions, plan, blueprint, and
> relevant tests. Implement only the settled scope, preserve existing ownership and memory
> semantics, run the specified checks, and append completion notes. Stop and return to the blueprint
> if a material design decision is missing.

## Priority order

```text
correct architecture
    > concise complete blueprint
        > correct implementation
            > optimization
```

Never optimize an incorrect or unsettled design, and never add process detail that does not improve
implementation safety.
