Add Baseline Explanations Here:

workUnits, weights, and smoothedBodyCost are different concepts.
- workUnits: How much computation is done in the executor
- weights: The value used similarly to workUnits to compute the threshold bands that smoothedBodyCost is compared to
- smoothedBodyCost: The measured body cost at runtime

workUnits != weights
The smoothedBodyCost is not measuring just the work. It is measuring the time from invoking the executor to returning from it.

Current real weights without factoring in contention or idling that can be passed to the core:
xs: 96

Current workUnits where DIRECT execution mode does not improve throughput:
216