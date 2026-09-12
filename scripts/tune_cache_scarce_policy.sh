#!/usr/bin/env bash
set -euo pipefail
export CACHE_TUNER_TASK=${CACHE_TUNER_TASK:-python/pareto-weight-calibration/tasks/cache-scarce-loop.json}
exec "$(dirname -- "${BASH_SOURCE[0]}")/tune_cache_policy.sh" "$@"
