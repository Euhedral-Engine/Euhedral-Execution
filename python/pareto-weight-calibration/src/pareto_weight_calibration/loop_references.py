"""Matched reference observations for scarce tuning, separate from search ancestry."""

from collections import defaultdict, Counter
from copy import deepcopy
import json, math, sqlite3
import numpy as np
from .parameter_tuning import pointer


def import_stores(spec, root, store):
  from .loop_data import compatible_families

  audit = Counter()
  expected = spec.benchmark.harness["trials"][0]["calibrationConfig"]
  for source in spec.historicalStores:
    db = sqlite3.connect(f"file:{(root / source).resolve()}?mode=ro", uri=True)
    try:
      rows = [
        json.loads(r[0])
        for r in db.execute("SELECT record FROM forks ORDER BY id")
      ]
    finally:
      db.close()
    imported = []
    for original in rows:
      cfg = original["config"]
      fn = original["function"]
      fixture = next(
          (
            f
            for f in spec.fixtures
            if original["workloadId"] == f["workloadId"]
               and all(
              cfg.get(k) == f[v]
              for k, v in spec.benchmark.fixtureBindings.items()
          )
          ),
          None,
      )
      if fixture is None:
        audit["outside scarce panel (retained in source)"] += 1
        continue
      allowed = set(spec.benchmark.historicalVariableKeys)
      # This calibration fixture has one real upstream handle per fragment. Its
      # productive count is bounded by one, below R7/R15/R23, even optimistically.
      bridge = (
          spec.historicalScarcityGateBridge
          and cfg.get("parallelSources") == 1
          and cfg.get("orderedSources") == 0
          and fixture.get("resolvedWorkers", 0) > 1
          and not cfg.get("cacheScarcityGateEnabled", False)
      )
      if bridge:
        allowed.add("cacheScarcityGateEnabled")
      if any(cfg.get(k) != v for k, v in expected.items() if k not in allowed):
        audit["incompatible runtime configuration"] += 1
        continue
      families = compatible_families(spec, fn)
      if fn is not None and not families:
        audit["incompatible function"] += 1
        continue
      if len(original["windows"]) != spec.benchmark.harness["trials"][0][
        "iterations"
      ] or not all(math.isfinite(v) and v > 0 for v in original["windows"]):
        audit["invalid windows"] += 1
        continue
      if not math.isclose(
          np.mean(original["windows"]), original["rawThroughput"], rel_tol=1e-12
      ):
        raise ValueError("historical mean mismatch")
      row = deepcopy(original)
      row["families"] = families
      row["historicalCompatibility"] = (
        "single-upstream scarcity gate invariant"
        if bridge
        else "same runtime config"
      )
      imported.append(row)
      audit["included"] += 1
    store.append(imported)
  return dict(audit)


def reference_parameters(spec):
  # Union is only an OFFLINE reference description. It never expands a runtime policy.
  unique = {}
  for f in spec.families:
    for p in f.parameters:
      unique.setdefault(p.path, p)
  return list(unique.values())


def reference_features(spec, function):
  ps = reference_parameters(spec)
  return (
    ([0.0] * len(ps) + [1.0])
    if function is None
    else [float(pointer(function, p.path)) - p.offset for p in ps] + [0.0]
  )


def model_parameters(spec, family):
  from .tuning_spec import Parameter

  extra = [
    p.model_copy(
        update={
          "name": "reference_" + str(i),
          "path": "/reference/" + str(i),
          "transform": "identity",
        }
    )
    for i, p in enumerate(reference_parameters(spec))
  ]
  return (
      list(family.parameters)
      + extra
      + [
        Parameter(
            name="reference_is_off",
            path="/reference/isOff",
            center=0.0,
            bounds=(0.0, 1.0),
        )
      ]
  )


def model_features(spec, family, theta, reference):
  from .dense_search import features

  x = features(family.parameters, np.asarray(theta))
  return np.column_stack(
      [x, np.tile(reference_features(spec, reference), (len(x), 1))]
  )


def matched_panels(spec, records, family=None, training=False):
  """Log ratios keep the exact same-campaign/block reference policy as a model input."""
  from .loop_data import require_static
  valid = {f["workloadId"] for f in spec.fixtures}
  by = defaultdict(dict)
  functions = {}
  for r in records:
    require_static(r)
    if r["workloadId"] not in valid:
      continue
    key = (r["campaignId"], r["blockId"], r["workloadId"])
    if r["policyId"] in by[key]:
      raise ValueError("duplicate fork in matched reference panel")
    by[key][r["policyId"]] = r
    functions[r["policyId"]] = r["function"]
  panels = defaultdict(list)
  frequency = Counter(
      r["policyId"] for r in records if r["function"] is not None)
  campaign_policies = defaultdict(set)
  for c, b, w in by:
    campaign_policies[c].update(by[c, b, w])
  for key, policies in by.items():
    camp, block, w = key
    for pid, r in policies.items():
      if r["function"] is None or (family and family.id not in r["families"]):
        continue
      declared = r.get("referencePolicyId")
      refs = [declared] if declared else ["POLICY_OFF"]
      if training:
        refs += sorted(
            campaign_policies[camp] - {"POLICY_OFF"},
            key=lambda p: (-frequency[p], p),
        )[:2]
      for ref in dict.fromkeys(refs):
        rr = policies.get(ref)
        if rr is None:
          continue
        panels[camp, pid, block, ref].append((r, rr))
  out = []
  for (camp, pid, block, ref), pairs in sorted(panels.items()):
    vals = {
      r["workloadId"]: math.log(r["rawThroughput"] / rr["rawThroughput"])
      for r, rr in pairs
    }
    targets = {
      o.name: (
        float(np.mean([vals[w] for w in o.workloads]))
        if all(w in vals for w in o.workloads)
        else None
      )
      for o in spec.responses
    }
    first = pairs[0][0]
    theta = first["families"][family.id] if family else None
    out.append(
        dict(
            rowId=f"{camp}/{pid}/{block}/{ref}",
            campaignId=camp,
            policyId=pid,
            blockId=block,
            theta=theta,
            thetaId=pid,
            targets=targets,
            workloadReturns=vals,
            referencePolicyId=ref,
            referenceFunction=functions[ref],
            sourceForks=[r["rowId"] for r, rr in pairs],
            matchedReference={r["workloadId"]: rr["rowId"] for r, rr in pairs},
        )
    )
  return out


def scored_panels(spec, records, campaign=None):
  from .loop_search import preference

  grouped = defaultdict(list)
  functions = {r["policyId"]: r["function"] for r in records}
  for p in matched_panels(spec, records):
    if campaign is None or p["campaignId"] == campaign:
      grouped[p["campaignId"], p["policyId"], p["referencePolicyId"]].append(p)
  out = []
  for (c, pid, ref), panels in grouped.items():
    w = {
      f["workloadId"]: float(
          np.mean(
              [
                p["workloadReturns"][f["workloadId"]]
                for p in panels
                if f["workloadId"] in p["workloadReturns"]
              ]
          )
      )
      for f in spec.fixtures
      if any(f["workloadId"] in p["workloadReturns"] for p in panels)
    }
    vals = np.array(
        [
          (
            np.mean([w[x] for x in o.workloads])
            if all(x in w for x in o.workloads)
            else np.nan
          )
          for o in spec.responses
        ]
    )
    if not np.isfinite(vals).all():
      continue
    score, vector = preference(spec, vals[None, :])
    out.append(
        dict(
            policyId=pid,
            searchFamily=next(
                (
                  r.get("searchFamily")
                  for r in records
                  if r["policyId"] == pid
                     and r["campaignId"] == c
                     and r.get("searchFamily")
                ),
                None,
            ),
            campaignId=c,
            function=functions[pid],
            score=float(score[0]),
            objectives=vector[0].tolist(),
            targets=dict(zip([o.name for o in spec.responses], vals.tolist())),
            workloadReturns=w,
            blocks=len(panels),
            referencePolicyId=ref,
            referenceKind=(
              "same-campaign matched incumbent"
              if ref != "POLICY_OFF"
              else "same-campaign matched OFF"
            ),
        )
    )
  return sorted(
      out,
      key=lambda r: (
        -r["score"],
        -r["objectives"][0],
        -r["objectives"][1],
        -r["objectives"][3],
        r["policyId"],
      ),
  )


def elite_archive(spec, records):
  """Measured comparison graph: no surrogate predictions and no borrowed OFF fork.

  Per-workload least squares reconciles same-campaign matched log-ratio edges.
  The resulting OFF-anchored network estimate is explicitly distinct from a fresh
  OFF-relative measurement. A campaign contributes one averaged edge per pair.
  """
  from .loop_search import preference

  functions = {
    r["policyId"]: r["function"] for r in records if r["function"] is not None
  }
  edges = defaultdict(list)
  for p in matched_panels(spec, records):
    for w, v in p["workloadReturns"].items():
      if p["policyId"] != p["referencePolicyId"]:
        edges[w, p["campaignId"], p["policyId"], p["referencePolicyId"]].append(
            v
        )
  utilities = defaultdict(dict)
  for fixture in spec.fixtures:
    w = fixture["workloadId"]
    ee = [
      (p, r, float(np.mean(v))) for (ww, c, p, r), v in edges.items() if ww == w
    ]
    connected = {"POLICY_OFF"}
    while True:
      add = {p for p, r, v in ee if r in connected} | {
        r for p, r, v in ee if p in connected
      }
      if add <= connected:
        break
      connected |= add
    ids = sorted(connected - {"POLICY_OFF"})
    index = {p: i for i, p in enumerate(ids)}
    matrix = []
    target = []
    for p, r, v in ee:
      if p not in connected or r not in connected:
        continue
      row = np.zeros(len(ids))
      row[index[p]] = 1
      if r != "POLICY_OFF":
        row[index[r]] -= 1
      matrix.append(row)
      target.append(v)
    if not matrix:
      continue
    values = np.linalg.lstsq(np.array(matrix), np.array(target), rcond=None)[0]
    for p, v in zip(ids, values):
      utilities[p][w] = float(v)
  out = []
  for p, w in utilities.items():
    if any(f["workloadId"] not in w for f in spec.fixtures):
      continue
    vals = np.array(
        [np.mean([w[x] for x in o.workloads]) for o in spec.responses])
    score, vec = preference(spec, vals[None, :])
    campaigns = {r["campaignId"] for r in records if r["policyId"] == p}
    out.append(
        dict(
            policyId=p,
            searchFamily=next(
                (
                  r.get("searchFamily")
                  for r in reversed(records)
                  if r["policyId"] == p and r.get("searchFamily")
                ),
                None,
            ),
            campaignId="measured-network",
            function=functions[p],
            score=float(score[0]),
            objectives=vec[0].tolist(),
            targets=dict(zip([o.name for o in spec.responses], vals.tolist())),
            workloadReturns=w,
            blocks=0,
            campaigns=len(campaigns),
            referenceKind="OFF-anchored matched-measurement network estimate",
        )
    )
  return sorted(
      out,
      key=lambda r: (
        -r["score"],
        -r["objectives"][0],
        -r["objectives"][1],
        -r["campaigns"],
        r["policyId"],
      ),
  )


def lineage(family, candidate, parent, parent_unit, role):
  unit = np.asarray(candidate["unit"])
  radius = parent["radius"] if parent else None
  delta = unit - np.asarray(parent_unit) if parent else None
  return dict(
      parentPolicyId=parent["policyId"] if parent else None,
      searchFamily=family.id,
      parentFamily=parent["family"] if parent else None,
      parentRadius=radius,
      parentNormalizedCoordinates=list(parent_unit) if parent else None,
      candidateNormalizedCoordinates=list(unit),
      normalizedDistanceFromParent=float(
        np.linalg.norm(delta)) if parent else None,
      normalizedEdgeFraction=(
        float(np.max(np.abs(delta)) / radius) if parent else None
      ),
      proposalRole=role,
  )


def radius_update(spec, region, origin=None, retained=False):
  if retained:
    at_minimum = region["radius"] <= spec.search.minimumRadius
    return dict(
        parentPolicyId=region["policyId"],
        parentFamily=region["family"],
        parentRadius=region["radius"],
        candidatePolicyId=region["policyId"],
        searchFamily=region["family"],
        normalizedDistanceFromParent=0.0,
        normalizedEdgeFraction=0.0,
        decision="RETAIN_MIN_RADIUS" if at_minimum else "RETAIN_SHRINK",
        oldRadius=region["radius"],
        newRadius=max(spec.search.minimumRadius,
                      region["radius"] * spec.search.shrink),
        reason=("no accepted improvement; local search already at minimum radius"
                if at_minimum else
                "no accepted improvement; narrowing local search"),
    )
  if origin is None or origin.get("parentPolicyId") is None:
    return dict(
        parentPolicyId=None,
        parentFamily=None,
        parentRadius=None,
        candidatePolicyId=region["policyId"],
        searchFamily=region["family"],
        normalizedDistanceFromParent=None,
        normalizedEdgeFraction=None,
        decision="NEW_BASIN",
        oldRadius=None,
        newRadius=spec.search.newBasinRadius,
        reason="global or historical basin; explicit new-basin radius",
    )
  r = origin["parentRadius"]
  edge = origin["normalizedEdgeFraction"]
  move = edge >= spec.search.edgeFraction
  return dict(
      origin,
      candidatePolicyId=region["policyId"],
      decision="EDGE_MOVE" if move else "INTERIOR_SHRINK",
      oldRadius=r,
      newRadius=r if move else max(spec.search.minimumRadius,
                                   r * spec.search.shrink),
      reason="explicit proposal parent, no ancestry reconstruction",
  )
