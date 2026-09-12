# Historical parser fixtures

These compact experiment, comparison and profile configurations are retained
from the historical productivity presets for parser and trial-resolution tests.
Tests inspect them without executing benchmarks. They are not raw run output.

Large historical CACHE campaign handoffs remain external. Their optional tests
use `EUHEDRAL_CACHE_TIMING_HANDOFF`, `EUHEDRAL_CACHE_FIXED_HANDOFF`, and
`EUHEDRAL_CACHE_CONFIRMATION_HANDOFF` (with
`EUHEDRAL_CACHE_CONFIRMATION_ORIGINAL` for the earlier manifest). Missing
external handoffs are explicitly skipped, not generated or benchmarked.
