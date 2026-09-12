# Pareto Weight Calibration Python Module

This module implements the external calibration pipeline for the eight CACHE-participation coefficients in Euhedral Execution.

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

## Parameter-driven tasks

See [TRAINING_HANDOFF.md](TRAINING_HANDOFF.md) for `pareto-train`, the task/manifest contracts,
synthetic timing dry run, validation boundaries and execution handoff.
