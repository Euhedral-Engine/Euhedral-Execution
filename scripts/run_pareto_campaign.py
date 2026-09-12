#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import time
from pathlib import Path

BASE_DIR = Path("/home/brandon/src/Euhedral-Execution")
BATCH_SIZE = 6
MIN_COOLDOWN_SEC = 45
MAX_COOLDOWN_SEC = 120
TARGET_TEMP_C = 48.0


def get_cpu_temp():
  try:
    out = subprocess.check_output(["sensors"], text=True,
                                  stderr=subprocess.DEVNULL)
    for line in out.splitlines():
      if "Package id 0:" in line:
        parts = line.split()
        temp_str = parts[3].replace("+", "").replace("°C", "")
        return float(temp_str)
  except Exception:
    pass
  return None


def cool_down():
  start = time.time()
  initial_temp = get_cpu_temp()
  temp_str = f"{initial_temp:.1f}°C" if initial_temp is not None else "unknown"
  print(
    f"  [Cooldown] Resting computer (initial temp: {temp_str}). Minimum wait: {MIN_COOLDOWN_SEC}s...",
    flush=True)
  time.sleep(MIN_COOLDOWN_SEC)

  while True:
    elapsed = time.time() - start
    temp = get_cpu_temp()
    temp_disp = f"{temp:.1f}°C" if temp is not None else "unknown"
    if temp is None or temp <= TARGET_TEMP_C or elapsed >= MAX_COOLDOWN_SEC:
      print(
        f"  [Cooldown] Cooled down after {elapsed:.1f}s (temp: {temp_disp}).",
        flush=True)
      break
    time.sleep(5)


def is_batch_complete(batch_file):
  with open(batch_file) as f:
    data = json.load(f)
  out_dir = BASE_DIR / data["artifacts"]["outputDirectory"]
  for trial in data["trials"]:
    trial_dir = out_dir / f"{trial['id']}_repeat_0"
    if not trial_dir.exists():
      return False
    fork_dirs = list(trial_dir.glob("fork_*"))
    if len(fork_dirs) < trial.get("forks", 2):
      return False
  return True


def run_experiment_batches(exp_id, exp_preset, batch_dir):
  print(f"\n=======================================================",
        flush=True)
  print(f"Starting Campaign Execution: {exp_id}", flush=True)
  print(f"=======================================================", flush=True)

  # 1. Generate batches
  cmd = [
    "java",
    "-cp",
    "benchmarks/build/euhedral-benchmark.jar:benchmarks/build/lib/*",
    "calibration.BatchSplitter",
    str(exp_preset),
    str(batch_dir),
    str(BATCH_SIZE),
  ]
  subprocess.run(cmd, cwd=BASE_DIR, check=True)

  # 2. Enumerate batches in sorted order
  batch_files = sorted(batch_dir.glob("batch_*.json"))
  total_batches = len(batch_files)
  print(f"Total batches to process for {exp_id}: {total_batches}", flush=True)

  for idx, batch_file in enumerate(batch_files, 1):
    if is_batch_complete(batch_file):
      print(
        f"[{exp_id}] Batch {idx}/{total_batches} ({batch_file.name}) already completed. Skipping.",
        flush=True)
      continue

    print(
      f"\n[{exp_id}] Running Batch {idx}/{total_batches} ({batch_file.name})...",
      flush=True)
    t0 = time.time()
    calib_cmd = [
      "benchmarks/build/bin/euhedral-calibration",
      "run",
      str(batch_file.relative_to(BASE_DIR)),
    ]
    res = subprocess.run(calib_cmd, cwd=BASE_DIR)
    elapsed = time.time() - t0
    if res.returncode != 0:
      print(
        f"FATAL: Batch {batch_file.name} failed with exit code {res.returncode}!",
        flush=True)
      sys.exit(res.returncode)

    print(f"[{exp_id}] Batch {idx}/{total_batches} finished in {elapsed:.1f}s.",
          flush=True)

    if idx < total_batches:
      cool_down()


def run_comparisons():
  print(f"\n=======================================================",
        flush=True)
  print(f"Starting Comparisons (16 configs in version order)", flush=True)
  print(f"=======================================================", flush=True)

  res = subprocess.run(
      "find benchmarks/src/main/presets/comparisons -maxdepth 1 -type f -name '3[6-8]-pareto-training-r*-k*-vs-k*.json' -print0 | sort -zV",
      shell=True,
      cwd=BASE_DIR,
      capture_output=True,
      check=True
  )
  raw_files = [f for f in res.stdout.decode().split('\0') if f.strip()]
  if len(raw_files) != 16:
    print(f"FATAL: Expected 16 comparison configs, found {len(raw_files)}",
          flush=True)
    sys.exit(1)

  for idx, comp_path in enumerate(raw_files, 1):
    comp_name = Path(comp_path).name
    print(f"[{idx}/16] Running comparison: {comp_name}...", flush=True)
    cmd = [
      "benchmarks/build/bin/euhedral-calibration",
      "compare",
      comp_path,
    ]
    comp_res = subprocess.run(cmd, cwd=BASE_DIR)
    if comp_res.returncode != 0:
      print(
        f"FATAL: Comparison {comp_name} failed with exit code {comp_res.returncode}!",
        flush=True)
      sys.exit(comp_res.returncode)


def audit_checksums():
  print(f"\n=======================================================",
        flush=True)
  print(f"Auditing Digest-Only Sidecars", flush=True)
  print(f"=======================================================", flush=True)

  roots = [
    "experiments/36-pareto-training-surface-r7",
    "experiments/37-pareto-training-surface-r15",
    "experiments/38-pareto-training-surface-r23",
  ]
  for root in roots:
    root_path = BASE_DIR / root
    if not root_path.exists():
      print(f"{root}: Directory does not exist!")
      continue
    sidecars = list(root_path.glob("**/*.sha256"))
    checked = 0
    bad = 0
    missing = 0
    for sidecar in sidecars:
      artifact = sidecar.with_suffix("")
      if not artifact.exists():
        missing += 1
        continue
      expected = sidecar.read_text().strip()
      actual_res = subprocess.run(["sha256sum", str(artifact)],
                                  capture_output=True, text=True)
      actual = actual_res.stdout.split()[0]
      checked += 1
      if actual != expected:
        bad += 1
    print(f"{root} checked={checked} bad={bad} missing={missing}", flush=True)


def main():
  experiments = [
    ("36-pareto-training-surface-r7",
     BASE_DIR / "benchmarks/src/main/presets/experiments/36-pareto-training-surface-r7.json",
     BASE_DIR / "benchmarks/build/tmp/batches/36"),
    ("37-pareto-training-surface-r15",
     BASE_DIR / "benchmarks/src/main/presets/experiments/37-pareto-training-surface-r15.json",
     BASE_DIR / "benchmarks/build/tmp/batches/37"),
    ("38-pareto-training-surface-r23",
     BASE_DIR / "benchmarks/src/main/presets/experiments/38-pareto-training-surface-r23.json",
     BASE_DIR / "benchmarks/build/tmp/batches/38"),
  ]

  for exp_id, exp_preset, batch_dir in experiments:
    run_experiment_batches(exp_id, exp_preset, batch_dir)
    cool_down()

  run_comparisons()
  audit_checksums()
  print("\nALL EXPERIMENTS AND COMPARISONS COMPLETED SUCCESSFULLY!", flush=True)


if __name__ == "__main__":
  main()
