#!/usr/bin/env bash
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo"
python=${CACHE_TUNER_PYTHON:-"$repo/.venv-cache-tuner/bin/python"}
if [[ ! -x "$python" ]]; then
  echo 'Create .venv-cache-tuner and install python/pareto-weight-calibration[tournament,dev], or set CACHE_TUNER_PYTHON.' >&2
  exit 1
fi
if [[ -z "${CACHE_TUNER_TASK:-}" ]]; then
  echo 'Set CACHE_TUNER_TASK to an explicit parameter-loop task file.' >&2
  exit 1
fi
exec mise exec -- "$python" -m pareto_weight_calibration.training_runner \
  --task "$CACHE_TUNER_TASK" "$@"
