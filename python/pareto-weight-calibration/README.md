# Pareto Weight Calibration Python Module

This module implements the external calibration pipeline for the eight CACHE-participation coefficients in Euhedral Execution.

See [`docs/design/productivity-participation-python-training-plan.md`](../../docs/design/productivity-participation-python-training-plan.md) and [`docs/design/step-2-python-loader-plan.md`](../../docs/design/step-2-python-loader-plan.md) for full design and architecture specifications.

## Installation

```bash
cd python/pareto-weight-calibration
pip install -e ".[dev]"
```

## CLI Usage

```bash
# Validate dataset manifest and checksums
python -m pareto_weight_calibration validate --manifest path/to/manifest.json

# Ingest and export joined pairs
python -m pareto_weight_calibration load --manifest path/to/manifest.json --output pairs.tsv

# Summarize pairs dataset
python -m pareto_weight_calibration summary --pairs pairs.tsv
```
