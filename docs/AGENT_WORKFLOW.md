# Staged Agent Workflow

Use this workflow for every non-trivial change. Its purpose is to make architectural reasoning a
deliberate, reviewable artifact, so implementation can execute a settled design instead of
reconstructing one.

## Model capability and reasoning-effort policy

Select the strongest available model appropriate for the phase. The plan must record the exact
selected model and reasoning effort for every prompt, but this workflow deliberately specifies
required capabilities rather than permanently binding a phase to one model name.

| Work | Required capability | Reasoning effort |
| --- | --- | --- |
| Planning and prompt-sequence design | Frontier reasoning model | Maximum available |
| Blueprint with data contracts, concurrency, mathematics, topology, or lifecycle changes | Frontier reasoning model | Maximum available |
| Blueprint with substantial integration, scheduling, packaging, or migration work | Frontier reasoning model | High |
| Blueprint for verification, audit, cleanup, or bounded interface work | Strong reasoning model | Medium |
| Implementation and iterative repair of an approved blueprint | Capability selected after the completed blueprint is assessed | Low only for bounded mechanical work; medium or high for broad systems integration |
| Implementation verification | Strong coding or verification model, raised when the acceptance surface remains coupled | Low to high |
| Blueprint-conformance audit | Dedicated audit model or frontier reasoning model | Medium to high |

The current mapping may be `gpt-5.6-sol` at `max` for planning and complex blueprints, a coding
model selected from the completed blueprint's actual demands for implementation, and Terra or Sol
for a conformance audit. These are examples, not permanent workflow requirements. A
`gpt-5.5 / low` implementation pass is appropriate only when the finished blueprint proves that
the work is bounded and mechanical. Do not use a lower effort to make an architectural decision,
and do not use an implementation prompt to reopen a decision that belongs in planning or a
blueprint.

## Pipeline

Each non-trivial feature proceeds in this order:

1. Planning and prompt-sequence design
2. Blueprint
3. Implementation
4. Implementation verification
5. Blueprint-conformance audit and handoff, when the feature needs them

A stage consumes the artifacts from earlier stages. It records newly discovered conclusions in its
own artifact and does not repeat repository discovery already captured there.

Before each prompt, inspect `git status --short`. Staged or unstaged changes that existed before
that prompt are user-owned: never commit, revert, delete, or otherwise modify them. If they block
the stage, stop and ask the developer for permission to resolve the conflict.

Start every stage on a dedicated `agent/...` branch from the completed prior-stage branch. Do not
merge, rebase, delete branches, commit, or push unless the developer has authorized that action.

## Stage 1: planning and prompt-sequence design

Run this stage with a frontier reasoning model at its maximum available reasoning effort. It must
first help the developer clarify the objective, constraints, scope, success criteria, and any
decision that materially changes the solution. Then it engineers the complete staged prompt
sequence.

Read:

- `AGENTS.md` and all applicable repository instructions;
- this workflow document;
- relevant architecture documentation;
- CI/CD configuration for build and test commands, when available; and
- the root and affected-module README files.

Identify affected components, ownership boundaries, dependencies, risks, restrictions, and the
validation surface. Ask the developer to resolve material ambiguities before producing the plan.
Do not generate or modify production code in this stage.

Create `docs/plans/<PLAN_NAME>.md`. The plan must be self-contained and include the objective,
developer requirements, scope, non-goals, constraints, affected components, success criteria,
validation strategy, known risks, branch lineage, and all context later stages need.

At its end, provide the prompts the developer should issue, grouped by phase. Rank them from most
to least reasoning-intensive, label every blueprint, verification, and audit prompt with its exact
model and reasoning effort, and give each implementation prompt a clearly labeled provisional
model and effort. Pair every blueprint with implementation, implementation-verification, and
blueprint-conformance audit prompts. Each prompt must name its required input artifacts, allowed
edits, prohibited work, output artifact, and handoff condition. The blueprint prompt must be
required to replace the provisional implementation selection after completing the capability
assessment below; implementation must not begin while its selection is still provisional.

## Stage 2: blueprint

Run each blueprint prompt using the model and effort selected in the plan. The blueprint prompt may
inspect current code and tests and may edit only the plan, its blueprint, and closely related
planning documentation. It must not write production code or partial implementations.

Write `docs/blueprints/<PHASE>-<FEATURE>.md` unless the feature plan defines a more specific
blueprint directory. A complete blueprint specifies:

- exact scope, non-goals, architecture, alternatives considered, and the selected design;
- each affected file and its intended modification, in dependency order;
- exact types, interfaces, formats, algorithms, defaults, invariants, and failure behavior;
- required mathematical precision, deterministic sorting and output order;
- memory semantics, safety, ownership, and memory-contamination avoidance;
- compatibility, migration, and deletion boundaries;
- test fixtures, assertions, acceptance criteria, and validation commands; and
- genuine unresolved blockers that require a new reasoning prompt.

An implementation must be able to execute directly from the blueprint. If it would need a design
choice, the blueprint is incomplete.

### Post-blueprint implementation-model gate

Model selection for implementation is a required blueprint output, not a planning-time constant.
After the blueprint is complete, reassess the implementation pass before committing the blueprint.
Record an `Implementation model reassessment` section that evaluates:

- the number of modules, ownership boundaries, source files, schemas, and lifecycle states that
  must be held together;
- whether the work combines concurrency or memory semantics, mathematical precision, filesystem
  safety, topology, recovery, migration, or deterministic serialization;
- how much prior context the implementation model must read and whether the blueprint provides a
  smaller exact context envelope;
- the breadth of compile repair, failure handling, and acceptance-test interactions; and
- evidence from earlier attempts with the proposed model or a lower-capability model.

Then select the implementation model and reasoning effort from those demands. `low` effort is only
for a bounded, mechanical translation with local failure modes. Use `medium` or `high`, and upgrade
the model capability as well as effort, when the implementation must preserve coupled systems
invariants across a broad context. A more detailed blueprint reduces design ambiguity; it does not
automatically reduce implementation complexity or context load.

Update the plan's implementation prompt label and body with the final selection before handoff. If
the selected model is unavailable, stop and request an explicit alternative instead of silently
downgrading. Do not assume that implementation verification can repair a knowingly under-capable
implementation pass.

## Stage 3: implementation

Run every implementation prompt with the model and effort selected by the completed blueprint's
implementation-model gate. Confirm that the plan prompt is no longer labeled provisional. Read
`AGENTS.md`, the plan, the applicable blueprint, and the completion notes and exact context envelope
named by that blueprint. Modify only the files and contracts enumerated by the blueprint, compile,
run the specified tests, fix defects within the settled design, and validate acceptance criteria
and architectural consistency.

Implementation must not introduce architecture. If it finds an unstated design decision or an
incompatible blueprint requirement, stop without deleting in-progress work. Append the conflict,
relevant evidence, and the needed decision to the blueprint; return the work to a blueprint prompt.

On success, append a concise completion record to the blueprint: changed files, commands run,
results, acceptance-criteria evidence, known environmental limits, and any approved deviations.

## Stage 4: implementation verification

Use a strong coding or verification model. Run the validation commands required by the blueprint,
check its acceptance criteria, and confirm that implementation defects are fixed only where the
blueprint already supplies the decision. This stage does not redesign architecture. Record commands,
results, skipped checks, and environmental limits in the blueprint completion record.

## Stage 5: blueprint-conformance audit

Use a dedicated audit model when available, or a frontier reasoning model otherwise. This is not an
open-ended code review: it validates that the completed implementation matches the approved design.
It may inspect code, tests, artifacts, and completion notes, but it must not propose architectural
improvements unless the implementation violates the blueprint.

Write `docs/audits/<PHASE>-<FEATURE>-conformance.md`, or the feature-specific audit directory
defined by the plan. Report each requirement as satisfied, deviated, unverified, or ambiguous;
include evidence, missing acceptance criteria, undocumented assumptions, and test limitations. A
deviation returns work to the relevant blueprint prompt unless the developer explicitly approves a
blueprint update.

## Prompt templates

Use this skeleton for a planning prompt:

> Use a frontier reasoning model at its maximum available reasoning effort. Read AGENTS.md, this
> workflow, the relevant architecture and CI documentation, and affected README files. First
> resolve material requirements with me; then write `docs/plans/<PLAN_NAME>.md`. Do not modify
> production code. Produce a ranked, phased blueprint/implementation/verification/audit prompt
> sequence with exact blueprint/verification/audit selections and clearly provisional
> implementation selections that each blueprint must finalize.

Use this skeleton for a blueprint prompt:

> Use a frontier or strong reasoning model with `<maximum|high|medium>` effort as required by the
> plan. Read AGENTS.md, the plan, prior blueprints and completion notes, and the relevant code and
> tests. Do not modify production code. Write `<BLUEPRINT_PATH>` as an implementation-ready
> blueprint: settle all design decisions, identify every file, specify contracts, algorithms,
> invariants, precision, memory semantics, deterministic ordering, failures, tests, and acceptance
> criteria. After the blueprint is complete, assess its implementation context, systems coupling,
> repair/test breadth, and prior model evidence; record the selected implementation model and
> effort, then update the plan's implementation prompt before handoff. If a material ambiguity
> remains, ask before finalizing the blueprint.

Use this skeleton for an implementation prompt:

> Use `<MODEL>` with `<low|medium|high>` reasoning effort as selected by the completed blueprint's
> implementation-model reassessment. Read AGENTS.md, `<PLAN_PATH>`, `<BLUEPRINT_PATH>`, and the
> exact prior context named by the blueprint. Implement only the enumerated checklist. Do not
> redesign the architecture. Run the required tests and validate acceptance criteria. If a new
> decision is required, stop and append the exact conflict and evidence to the blueprint.
> Otherwise, append completion notes with changed files and test results.

Use this skeleton for an implementation-verification prompt:

> Use the selected strong coding or verification model with `<low|medium|high>` reasoning effort. Read
> AGENTS.md, the plan, `<BLUEPRINT_PATH>`, the implementation, and its completion notes. Run every
> validation command and check every acceptance criterion. Fix only implementation defects whose
> resolution is already settled by the blueprint. Append commands, results, skipped checks, and
> environmental limits to the completion record.

Use this skeleton for a blueprint-conformance audit prompt:

> Use the selected dedicated audit model or frontier reasoning model with `<medium|high>` reasoning
> effort. Read the approved blueprint for this branch, the implementation, and the completion
> notes. Verify that every blueprint requirement is satisfied, identify any deviations,
> undocumented assumptions, or missing acceptance criteria, and produce a report at
> `<AUDIT_PATH>`. Do not propose architectural improvements unless the implementation violates the
> blueprint.

## Priority order

```text
correct architecture
    > complete blueprint
        > correct implementation
            > optimization
```

Never optimize an incorrect or unsettled design.
