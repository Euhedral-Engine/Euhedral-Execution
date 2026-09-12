"""Audited whole-scheduler history adapter with exact parameter grouping and two controls."""

from copy import deepcopy
from collections import defaultdict
from fnmatch import fnmatch
import json
import math
import statistics
from pathlib import Path
from .training_spec import digest
from .parameter_tuning import pointer
from .cache_timing_confirmation import measurement_windows


def sha(path):
  import hashlib
  value = hashlib.sha256()
  with path.open('rb') as stream:
    for block in iter(lambda: stream.read(8 * 1024 * 1024), b''):
      value.update(block)
  return value.hexdigest()


def read(path):
  return json.loads(path.read_text())


def theta_id(theta):
  return "theta-" + digest([float(x) for x in theta])[:20]


def compatible_function(function, center, parameters):
  if function is None:
    return None, "fixed path; no live parameter vector"
  restored = deepcopy(function)
  try:
    values = [float(pointer(function, p.path)) - p.offset for p in parameters]
    for p in parameters:
      pointer(restored, p.path, p.center + p.offset, True)
  except (KeyError, IndexError, TypeError, ValueError):
    return None, "parameter mapping unavailable"
  if restored != center:
    return (
      None,
      "inactive coefficients, transforms, bounds or output semantics differ",
    )
  if not all(math.isfinite(v) for v in values):
    return None, "nonfinite parameters"
  # Historical compatible points may lie outside the current search box; never discard for that reason.
  return values, None


def build(spec, root):
  if spec.dataset.archive:
    import tempfile
    import shutil
    from .run_archive import restore

    with tempfile.TemporaryDirectory(prefix="historical-tsv-") as tmp:
      materialized = Path(tmp)
      metadata = restore(root / spec.dataset.archive, materialized)
      for name in [spec.dataset.centerArtifact, spec.dataset.referenceIdentity]:
        if name and not (materialized / name).exists():
          (materialized / name).parent.mkdir(parents=True, exist_ok=True)
          shutil.copyfile(root / name, materialized / name)
      result = _build(spec, materialized, Path(metadata["originalRoot"]))
      result["archive"] = dict(
          path=spec.dataset.archive, sha256=sha(root / spec.dataset.archive)
      )
      return result
  return _build(spec, root, root)


def _build(spec, root, original_root):
  ds = spec.dataset
  paths = sorted({p for pattern in ds.discovery for p in root.glob(pattern)})
  if ds.adapter == "response_rows":
    rows = []
    for p in paths:
      rows.extend(read(p))
    if len({r["rowId"] for r in rows}) != len(rows):
      raise ValueError("duplicate campaign-qualified row ID")
    for r in rows:
      if r["thetaId"] != theta_id(r["theta"]):
        raise ValueError("incorrect theta group")
    return dict(
        rows=rows,
        forks=[],
        controls=[],
        audit=[],
        inputHashes={str(p.relative_to(root)): sha(p) for p in paths},
    )
  center = read(root / ds.centerArtifact)
  reference = read(root / ds.referenceIdentity)
  identity_hashes = reference["sourceHashes"]
  fixtures = {f["workloadId"]: f for f in ds.fixtures}
  inputs = {
    ds.centerArtifact: sha(root / ds.centerArtifact),
    ds.referenceIdentity: sha(root / ds.referenceIdentity),
  }
  records = []
  controls = []
  audit = []
  covered = set()
  seen_runs = set()
  grouped = defaultdict(list)
  for path in paths:
    source = read(path)
    inputs[str(path.relative_to(root))] = sha(path)
    for receipt_name in ["evidence_manifest.json", "dataset.json"]:
      receipt_path = path.parent / receipt_name
      if receipt_path.exists():
        receipt = read(receipt_path)
        inputs[str(receipt_path.relative_to(root))] = sha(receipt_path)
        for name, expected in receipt.get(
            "files", receipt.get("hashes", {})
        ).items():
          if sha(receipt_path.parent / name) != expected:
            raise ValueError("evidence receipt mismatch: " + name)
    for row in source:
      cfg = row["config"].get("calibrationConfig", row["config"])
      provenance = row["provenance"]
      raw = root / Path(provenance["configPath"]).relative_to(original_root)
      log = root / Path(provenance["logPath"]).relative_to(original_root)
      campaign = raw.parent.parent.name
      covered.add(raw.resolve())
      policy = row.get("policyId", row.get("policyKind", "unnamed"))
      key = (campaign, policy, str(path.relative_to(root)))
      reason = None
      raw_id = str(raw.resolve())
      if raw_id in seen_runs:
        reason = "duplicate retained copy of a fork"
      seen_runs.add(raw_id)
      function = pointer(cfg, ds.functionPath)
      theta, failure = compatible_function(function, center, spec.parameters)
      is_off = function is None and all(
          cfg.get(k) == v for k, v in ds.fixedControl.items()
      )
      if failure and not is_off:
        reason = reason or failure
      identity_path = raw.parent.parent / "identity.json"
      if not identity_path.exists():
        reason = reason or "missing launch identity"
      else:
        identity = read(identity_path)
        inputs[str(identity_path.relative_to(root))] = sha(identity_path)
        mismatches = []
        for name, h in identity_hashes.items():
          if not any(fnmatch(name, pat) for pat in ds.runtimeSourcePatterns):
            continue
          value = identity.get("sourceHashes", {}).get(name)
          if value != h and value not in ds.acceptedSourceHashes.get(
              name, ()
          ):
            mismatches.append(name)
        if mismatches:
          reason = reason or "runtime identity differs: " + ",".join(
              mismatches
          )
      fixture = fixtures.get(row["workloadId"])
      if fixture is None:
        reason = reason or "workload outside declared response panel"
      else:
        expected = dict(
            ds.expectedConfig,
            cpuSet=fixture["cpuSet"],
            parallelSources=fixture["parallelSources"],
            workUnits=fixture["workUnits"],
        )
        if any(
            cfg.get(k) != v
            for k, v in expected.items()
            if k not in ds.varyingConfigKeys
        ):
          reason = reason or "non-treatment fixture config differs"
        if any(
            cfg.get(k) != expected[k]
            for k in ["cpuSet", "parallelSources", "workUnits"]
        ):
          reason = reason or "resolved CPU/source/body fixture differs"
      # Included forks must prove content and exact execution setup, not merely carry a hash string.
      if reason is None:
        if (
            sha(raw) != provenance["configSha256"]
            or sha(log) != provenance["logSha256"]
        ):
          raise ValueError("raw provenance hash mismatch")
        trial = read(raw)
        text = log.read_text()
        if trial["calibrationConfig"] != cfg:
          raise ValueError("raw/config mismatch")
        if any(trial.get(k) != v for k, v in ds.schedule.items()):
          reason = "benchmark schedule differs"
        if any(
            option
            not in text.split("# VM options: ", 1)[-1].splitlines()[0].split()
            for option in ds.requiredJvmOptions
        ):
          reason = "benchmark JVM mode differs"
        if any(header not in text.splitlines() for header in ds.runtimeHeaders):
          reason = "JVM/JMH runtime header differs"
      if reason is not None:
        grouped[key].append(
            dict(
                included=False,
                reason=reason,
                workload=row["workloadId"],
                theta=theta,
            )
        )
        continue
      values = measurement_windows(text, trial["iterations"])
      outcome = row.get("outcome", row)
      if (
          values != outcome["measurementWindows"]
          or statistics.mean(values) != outcome["executionsPerSecond"]
      ):
        raise ValueError("raw windows/mean mismatch")
      observation = dict(
          rowId=campaign + "/" + row["runId"] + "/fork-" + row["forkId"],
          campaignId=campaign,
          policyId=policy,
          thetaId=theta_id(theta) if theta else None,
          theta=theta,
          workloadId=row["workloadId"],
          topology=fixture["resolvedWorkers"],
          sourceRegime=fixture["workloadClass"],
          bodyRegime=fixture.get("bodyRegime", str(fixture["workUnits"])),
          blockId=str(row["passId"]),
          forkId=str(row["forkId"]),
          rawThroughput=outcome["executionsPerSecond"],
          windows=values,
          provenance=dict(
              configPath=str(original_root / raw.relative_to(root)),
              configSha256=sha(raw),
              logPath=str(original_root / log.relative_to(root)),
              logSha256=sha(log),
              identityPath=str(original_root / identity_path.relative_to(root)),
              identitySha256=sha(identity_path),
          ),
      )
      (controls if is_off else records).append(observation)
      grouped[key].append(
          dict(
              included=True,
              reason=(
                "matched production control"
                if is_off
                else "exact parameter family and compatible runtime/fixture"
              ),
              workload=row["workloadId"],
              theta=theta,
          )
      )
  # Discover older raw campaigns lacking arms.json as well, and account for every raw candidate source.
  uncovered = defaultdict(list)
  for pattern in ds.rawDiscovery:
    for raw in root.glob(pattern):
      if raw.resolve() in covered:
        continue
      trial = read(raw)
      cfg = trial.get("calibrationConfig", {})
      function = cfg.get("cacheTimingFunction")
      key = (raw.parent.parent.name, trial.get("labels") or {})
      policy = key[1].get(
          "policyId", "fixed" if function is None else "uncollected_live"
      )
      uncovered[(key[0], policy)].append((raw, function))
  for (campaign, policy), items in sorted(uncovered.items()):
    compatible = any(
        compatible_function(f, center, spec.parameters)[0] is not None
        for _, f in items
    )
    if compatible:
      raise ValueError(
          "compatible uncollected raw history requires a collected source: "
          + campaign
      )
    audit.append(
        dict(
            campaign=campaign,
            policy=policy,
            source="raw discovery",
            status="EXCLUDED",
            reason="fixed or incompatible runtime family; no exact live parameter response",
            forks=len(items),
            theta=None,
            workloads=[],
            centerAvailable=False,
            offAvailable=False,
        )
    )
  off = {(r["campaignId"], r["workloadId"], r["blockId"]): r for r in controls}
  centers = {
    (r["campaignId"], r["workloadId"], r["blockId"]): r
    for r in records
    if r["theta"] == [p.center for p in spec.parameters]
  }
  usable = []
  for r in records:
    key = (r["campaignId"], r["workloadId"], r["blockId"])
    o = off.get(key)
    c = centers.get(key)
    r.update(
        offId=o["rowId"] if o else None,
        centerId=c["rowId"] if c else None,
        responses={
          "off": math.log(
            r["rawThroughput"] / o["rawThroughput"]) if o else None,
          "center": (
            math.log(r["rawThroughput"] / c["rawThroughput"]) if c else None
          ),
        },
    )
    if o is None:
      raise ValueError("included candidate lacks matched OFF: " + r["rowId"])
    usable.append(r)
  for (campaign, policy, source), items in sorted(grouped.items()):
    for included in [True, False]:
      subset = [x for x in items if x["included"] == included]
      if not subset:
        continue
      audit.append(
          dict(
              campaign=campaign,
              policy=policy,
              source=source,
              status="INCLUDED" if included else "EXCLUDED",
              reason="; ".join(sorted({x["reason"] for x in subset})),
              forks=len(subset),
              theta=subset[0]["theta"],
              workloads=sorted({x["workload"] for x in subset}),
              centerAvailable=any(k[0] == campaign for k in centers),
              offAvailable=any(k[0] == campaign for k in off),
          )
      )
  bundles = defaultdict(list)
  for r in usable:
    bundles[(r["campaignId"], r["thetaId"], r["blockId"])].append(r)
  rows = []
  for (campaign, theta, block), forks in sorted(bundles.items()):
    by_workload = {f["workloadId"]: f for f in forks}
    if len(by_workload) != len(forks):
      raise ValueError("duplicate policy/workload/block")
    targets = {}
    for system in spec.systems:
      for response in spec.responses:
        vals = [
          by_workload[w]["responses"][system]
          for w in response.workloads
          if w in by_workload
        ]
        targets[system + ":" + response.name] = (
          statistics.mean(vals)
          if len(vals) == len(response.workloads)
             and all(v is not None for v in vals)
          else None
        )
    rows.append(
        dict(
            rowId=campaign + "/" + theta + "/block-" + block,
            campaignId=campaign,
            thetaId=theta,
            theta=forks[0]["theta"],
            blockId=block,
            targets=targets,
            sourceForks=[f["rowId"] for f in forks],
        )
    )
  return dict(
      rows=rows,
      forks=usable,
      controls=controls,
      audit=audit,
      inputHashes=inputs,
      unit="JVM fork; model rows are complete policy/block panels with missing targets, not additional replicates",
  )
