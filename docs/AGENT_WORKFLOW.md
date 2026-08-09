# Staged Agent Workflow

Use this workflow for every non-trivial change. Its purpose is to make architectural reasoning a
deliberate, reviewable artifact, so implementation can execute a settled design instead of
reconstructing one.

## Model capability and reasoning-effort policy

Select the strongest available model appropriate for the phase. The plan must record the exact
selected model and reasoning effort for every prompt, but this workflow deliberately specifies
required capabilities rather than permanently binding a phase to one model name.

| Work                                                                                    | Required capability                                                           | Reasoning effort                                                                   |
|-----------------------------------------------------------------------------------------|-------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| Planning and prompt-sequence design                                                     | Frontier reasoning model                                                      | Maximum available                                                                  |
| Blueprint with data contracts, concurrency, mathematics, topology, or lifecycle changes | Frontier reasoning model                                                      | Maximum available                                                                  |
| Blueprint with substantial integration, scheduling, packaging, or migration work        | Frontier reasoning model                                                      | High                                                                               |
| Blueprint for verification, audit, cleanup, or bounded interface work                   | Strong reasoning model                                                        | Medium                                                                             |
| Implementation and iterative repair of an approved blueprint                            | Capability selected after the completed blueprint is assessed                 | Low only for bounded mechanical work; medium or high for broad systems integration |
| Implementation conformance and manual review                                            | Strong coding/audit model, raised when the acceptance surface remains coupled | Medium to high                                                                     |

The current mapping may be `gpt-5.6-sol` at `max` for planning and complex blueprints, a coding
model selected from the completed blueprint's actual demands for implementation, and Terra or Sol
for a conformance audit. These are examples, not permanent workflow requirements. A
`gpt-5.5 / low` implementation pass is appropriate only when the finished blueprint proves that
the work is bounded and mechanical. Do not use a lower effort to make an architectural decision,
and do not use an implementation prompt to reopen a decision that belongs in planning or a
blueprint.

## Work model

| Workflow artifact         | Working analogy | Purpose                                                   |
|---------------------------|-----------------|-----------------------------------------------------------|
| Phase                     | Story           | A developer-visible outcome with a coherent boundary      |
| Prompt                    | Action item     | One bounded unit of agent work                            |
| Blueprint                 | Ticket          | The settled contract for one implementable responsibility |
| Conformance/manual review | Pull request    | Evidence that the implementation satisfies its contract   |
| Audit                     | QA              | Independent conformance and quality assessment            |

The workflow forms an n-ary tree of context and results. Planning supplies the root context. A
phase splits context into independently owned blueprints until each leaf is executable by a
non-frontier implementation agent. Completion records, conformance evidence, and audits then flow
up through parent blueprints and phases to the root plan. Parent artifacts summarize and link to
their children; they must not force downstream agents to reread the full feature history.

## Toolchain default

Every Java command and Gradle build defaults to the exact versions declared in the
repository's `mise.toml`. Use `mise exec --` for those commands when available. A restricted
environment may use the documented explicit pinned-tool fallback only when `mise` cannot be used;
record the substituted versions and any verification limit. A module's lower Java release target
does not permit silently defaulting to an older JDK or Gradle.

## Pipeline

Each non-trivial feature proceeds in this order:

1. Planning and prompt-sequence design
2. Blueprint
3. Implementation
4. Conformance check, manual review, and handoff

A phase consumes artifacts from earlier phases and records newly discovered conclusions in its own
artifact. Within a phase, prompts are small action items: blueprint, implementation, conformance
check, and manual-review action items may exist for each independently deliverable chunk.

Before each prompt, inspect `git status --short`. Staged or unstaged changes that existed before
that prompt are user-owned: never commit, revert, delete, or otherwise modify them. If they block
the phase, stop and ask the developer for permission to resolve the conflict.

Use the phase-branch format `feature_name/phase-N-title`. A root phase branch begins from the
completed prior root phase. Sub-phase branches use the same feature and phase prefix with a
specific child suffix, and must be merged into their root phase branch when their action item is
complete. Do not begin a later root phase from an unmerged sub-phase result.

Do not merge, rebase, delete branches, commit, or push unless the developer has authorized that
action. When authorized, a phase may include uncommitted changes carried from the current or
immediately previous phase in its own commit, or amend the immediately previous phase commit,
provided it first inspects the diff and does not include pre-existing user-owned changes.

## Phase 1: planning and prompt-sequence design

Run this phase with a frontier reasoning model at its maximum available reasoning effort. It must
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
verification surface. Ask the developer to resolve material ambiguities before producing the plan.
Do not generate or modify production code in this phase.

Create `docs/plans/<PLAN_NAME>.md`. The plan must be self-contained and include the objective,
developer requirements, scope, non-goals, constraints, affected components, success criteria,
conformance/manual-review strategy, known risks, branch lineage, and all context later stages need.

At its end, provide the prompts the developer should issue, grouped by phase. Rank them from most
to least reasoning-intensive, label every blueprint, conformance, and manual-review prompt with its
exact
model and reasoning effort, and give each implementation prompt a clearly labeled provisional
model and effort. Pair every blueprint with implementation, conformance, and manual-review
action items. Each prompt must name its required input artifacts, allowed edits, prohibited work,
output artifact, branch, parent artifact, and handoff condition. The plan must name the initial
package or module ownership boundaries for each phase. The blueprint prompt must replace the
provisional implementation selection after the capability assessment below; implementation must
not begin while that selection remains provisional.

## Phase 2: blueprint

Run each blueprint prompt using the model and effort selected in the plan. The blueprint prompt may
inspect current code and tests and may edit only the plan, its blueprint, and closely related
planning documentation. It must not write production code or partial implementations.

Write `docs/blueprints/<PHASE>-<FEATURE>.md` unless the feature plan defines a more specific
blueprint directory. A complete blueprint specifies:

- exact scope, non-goals, architecture, alternatives considered, and the selected design;
- package and naming conventions, package ownership boundaries, and the data flow between them;
- intricate or high-reasoning types, data schemas, public interfaces, formats, algorithms,
  defaults, invariants, and failure behavior;
- required mathematical precision, deterministic sorting and output order;
- memory semantics, safety, ownership, and memory-contamination avoidance;
- compatibility, migration, and deletion boundaries;
- test fixtures, assertions, acceptance criteria, and conformance commands; and
- genuine unresolved blockers that require a new reasoning prompt.

Do not enumerate every minor class or mechanically derived file. The blueprint constrains package
structure, high-reasoning classes, data contracts, inputs, outputs, and the acceptance surface; the
implementation agent owns local reasoning within those constraints. An implementation must be able
to execute directly from the blueprint without making an architectural choice. If it would need
one, the blueprint is incomplete.

### Blueprint sizing and split gate

Before selecting the implementation model, evaluate every drafted blueprint:

- Can one implementation agent reasonably keep the required context in working memory?
- Does this blueprint define more than one independent responsibility?
- Can two portions be implemented and validated independently?
- Are there ownership boundaries that naturally divide the work?

If any answer shows that the scope exceeds what a non-frontier implementation agent can reasonably
execute, split the blueprint into child blueprints before handoff. Give each child a bounded
responsibility, package ownership, inputs and outputs, acceptance criteria, and
conformance/manual-review action item. Update the phase's subsequent prompts, branch lineage,
parent blueprint, and plan so they address the children rather than the oversized parent; then
re-run this gate for every child. A
blueprint that cannot be split further is an exception and must explain why its coupling is
irreducible.

An explicit developer instruction may instead authorize one integrated conformance/manual-review
action after all child implementations for a named phase. Record that exception in the parent
blueprint, plan rules, branch lineage, artifact index, and every downstream prompt. Child
blueprints and implementations must still be sequentially reviewed and merged, each implementation
must run and record its owned tests, and the final conformance action must classify every child and
parent acceptance criterion. Do not create intermediate validation/conformance/audit branches or
artifacts for such a phase. Without that explicit recorded authorization, the per-child rule above
remains the default.

When the per-child rule applies, conformance and audit action items consume only the child's
context envelope and summarized parent context. They should require no frontier reasoning unless
the child is demonstrably irreducible or reveals a new unsettled architectural decision.

On creation, append a concise developer-review summary to the parent plan or phase record:
purpose, package boundaries, key contracts, child work units, selected implementation capability,
risks, and unresolved items. This is the normal review surface; the full blueprint remains
available for deep inspection.

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
the selected model is unavailable, split the blueprint further or stop and request an explicit
alternative; do not silently downgrade. Do not assume that conformance or manual review can repair a
knowingly under-capable implementation pass.

## Phase 3: implementation

Run every implementation prompt with the model and effort selected by the completed blueprint's
implementation-model gate. Confirm that the plan prompt is no longer labeled provisional. Read
`AGENTS.md`, the plan, the applicable blueprint, and the completion notes and exact context envelope
named by that blueprint. Modify only the files and contracts enumerated by the blueprint, compile,
run the specified tests, fix defects within the settled design, and verify acceptance criteria
and architectural consistency.

When creating a class or method, or when changing a method signature, add a Markdown-style `///`
comment at the declaration. Explain the type's or method's purpose and define any non-obvious
parameter, return value, capability, ownership, ordering, unit, or failure semantics. Keep the
comment adjacent to the declaration and update it when the signature or behavior changes.

Implementation must not introduce architecture. If it finds an unstated design decision or an
incompatible blueprint requirement, stop without deleting in-progress work. Append the conflict,
relevant evidence, and the needed decision to the blueprint; return the work to a blueprint prompt.

On success, append a concise completion record to the blueprint: changed files, commands run,
results, acceptance-criteria evidence, known environmental limits, and any approved deviations.
Update `AGENTS.md` after the action item with a compact phase-status block that identifies the
completed root phase and completed or active sub-phases, with links to their summaries. When the
root phase is complete and its child branches have been merged, delete that phase-status block from
`AGENTS.md`; enduring instructions belong in the plan or workflow, not stale status text.

## Phase 4: conformance check and manual review

Use a strong coding/audit model. Check every
acceptance criterion, then write `docs/audits/<PHASE>-<FEATURE>-conformance.md`, or the
feature-specific audit path defined by the plan. Classify each requirement as satisfied, deviated,
unverified, or ambiguous and include evidence, missing acceptance criteria, undocumented
assumptions, and test limitations.

The combined step may make minor blueprint-settled corrections discovered during conformance or
manual review, including missing deterministic coverage and naming/formatting defects. It must not
redesign architecture or introduce a decision the blueprint did
not settle. Record commands, results, fixes, skipped checks, and environmental limits in the
blueprint completion record. A remaining material deviation returns work to the relevant blueprint
prompt unless the developer explicitly approves a blueprint update.

## Prompt templates

Use this skeleton for a planning prompt:

> Use a frontier reasoning model at its maximum available reasoning effort. Read AGENTS.md, this
> workflow, the relevant architecture and CI documentation, and affected README files. First
> resolve material requirements with me; then write `docs/plans/<PLAN_NAME>.md`. Do not modify
> production code. Produce a ranked, phased blueprint/implementation/conformance/manual-review prompt
> sequence. Give every action item its parent artifact, branch, allowed edits, output, handoff,
> and initial package ownership. Use clearly provisional implementation selections that each
> blueprint must finalize.

Use this skeleton for a blueprint prompt:

> Use a frontier or strong reasoning model with `<maximum|high|medium>` effort as required by the
> plan. Read AGENTS.md, the plan, prior blueprints and completion notes, and the relevant code and
> tests. Do not modify production code. Write `<BLUEPRINT_PATH>` as an implementation-ready
> blueprint: settle architectural decisions, specify package and naming conventions, high-reasoning
> classes, contracts, algorithms, invariants, precision, memory semantics, deterministic ordering,
> failures, tests, and acceptance criteria. Do not enumerate minor files unnecessarily. Apply the
> sizing and split gate; if needed, create child blueprints and update all downstream action items.
> Then assess implementation context, systems coupling, repair/test breadth, and prior model
> evidence; record the selected implementation model and effort, update the plan's implementation
> prompt, and append the concise developer-review summary before handoff. If a material ambiguity
> remains, ask before finalizing the blueprint.

Use this skeleton for an implementation prompt:

> Use `<MODEL>` with `<low|medium|high>` reasoning effort as selected by the completed blueprint's
> implementation-model reassessment. Read AGENTS.md, `<PLAN_PATH>`, `<BLUEPRINT_PATH>`, and the
> exact prior context named by the blueprint. Implement only the enumerated checklist. Do not
> redesign the architecture. Run the required tests and validate acceptance criteria. If a new
> decision is required, stop and append the exact conflict and evidence to the blueprint.
> Otherwise, append completion notes with changed files and test results, and update the temporary
> `AGENTS.md` phase-status block. Do not start a sibling or later root phase from an unmerged child
> branch.

Use this skeleton for a combined verification-and-conformance-audit prompt:

> Use the selected strong coding/audit model with `<medium|high>` reasoning effort. Read AGENTS.md,
> the plan, `<BLUEPRINT_PATH>`, the implementation, and its completion notes. Check every
> acceptance criterion. Make only minor blueprint-settled fixes, including deterministic coverage,
> naming and formatting corrections; do not redesign the
> architecture. Then classify every blueprint requirement as satisfied, deviated, unverified, or
> ambiguous in `<AUDIT_PATH>`, with evidence and limitations. Append commands, results, fixes, and
> skipped checks to the completion record. Use only the child context envelope and summarized parent
> context unless a new architectural decision makes frontier reasoning necessary.

## Priority order

```text
correct architecture
    > complete blueprint
        > correct implementation
            > optimization
```

Never optimize an incorrect or unsettled design.
