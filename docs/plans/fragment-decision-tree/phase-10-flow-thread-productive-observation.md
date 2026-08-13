# Phase 10 FlowThread Productive-Observation Plan

Status: complete - FlowThread observation accepted

Intensity: high implementation and maximum evidence review

## Objective

Replace the rejected empty-source re-probe with evidence produced during the original source
service. Preserve the conformance-tested worker-local, observational, stale, and unsynchronized
productive-handle model. Restore the productive-count production root only after semantic tests and
the unchanged same-build overhead gates pass.

## Scope and success criteria

The change is limited to `FlowThread` service evidence, `UpstreamQueue` transition accounting, the
existing `LatticeVertex.UpstreamInterceptor` validity/stop observation, focused Core tests, and the
retained Phase 9 benchmark fixture. Do not change the decision-tree structure, body-cost estimator,
batching, request ordering, routing, caches, topology, or producer publication.

Success requires:

- one original request or pull per handle service, with no classification probe;
- positive pull or synchronous request production marks the owner-local handle productive;
- a normal empty pull marks it nonproductive;
- request-only misses, rejected stops, failed acquisition, exception, cancellation, and invalid
  lifecycle service preserve the prior observation;
- the real Phase 8 queue fixtures retain their live/productive counts;
- productive-fast and empty-miss controls pass the one-percent median and 98-percent lowest-fork
  gates with both workers participating; and
- if those gates pass, the completed-batch production input changes from live to productive handles
  and the four bounded normal-policy rows retain their expected modes.

## Current-state findings

The uncommitted implementation correctly removes the second pull and wraps the original stop
predicate. Its `FlowThread` integration is incomplete: it treats an unchanged counter as production,
does not support a missing fallback context, conditions state updates on the interceptor already
having changed state, loses request-only preservation, and can create false empty evidence after
failed service. Removing the existing requested-push observation also loses direct synchronous push
evidence. The `WorkRequester` edit discards existing remote-cache pull accounting and is unrelated to
handle classification.

`FlowContext.satisfiedRequest` is owner-thread state incremented by synchronous requested routing
into a remote cache. `satisfiedPull` records locally consumed work and can include the original
direct-source pull result. Counter values are cumulative only within the worker's current control
operation and may be reset before, but not during, an ordinary synchronous service. A changed value,
rather than signed subtraction, is sufficient positive evidence and remains valid across signed
overflow. Direct requested pushes retain the interceptor's existing service-local observation so
ordered/direct routing is not lost.

## Work sequence

1. Complete the bounded design in
   `docs/blueprints/fragment-decision-tree/phase-10-flow-thread-productive-observation.md`.
2. Refine the manual Core changes and add deterministic service/lifecycle tests.
3. Run focused and required Gradle checks.
4. Run the retained same-build overhead fixture without weakening its gates.
5. If accepted, restore only the completed-batch productive-count input and bounded policy fixture,
   run the four confirmation rows, and append exactly one completion outcome to the blueprint.
