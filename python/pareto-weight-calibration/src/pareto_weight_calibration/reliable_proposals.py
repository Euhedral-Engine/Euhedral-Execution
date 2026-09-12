"""Reliability-aware Pareto proposals and JSON benchmark preparation; no benchmark execution."""

from collections import Counter
from copy import deepcopy
import math
import json
from pathlib import Path
import numpy as np
from scipy.spatial.distance import cdist
from sklearn.cluster import KMeans
from .proposal_reliability import (
  policy_view,
  search_scores,
  rule_scores,
  history_exclusions,
)
from .parameter_tuning import pointer
from .training_spec import digest


def nondominated(values):
  """Keep physically distinct theta on prediction ties; equality is not measured equivalence."""
  values = np.asarray(values, float)
  if not np.isfinite(values).all():
    raise ValueError("finite Pareto objectives required")
  if not len(values):
    return np.array([], int)
  if len(values) > 2048:
    from moocore import is_nondominated
    return np.flatnonzero(
      is_nondominated(values, maximise=True, keep_weakly=True))
  unique, inverse = np.unique(values, axis=0, return_inverse=True)
  alive = np.ones(len(unique), bool)
  for i in range(len(unique) - 1, -1, -1):
    if not alive[i]:
      continue
    ids = np.flatnonzero(alive)
    rows = unique[ids]
    dominated = np.all(unique[i] >= rows, axis=1) & np.any(unique[i] > rows,
                                                           axis=1)
    alive[ids[dominated]] = False
  return np.flatnonzero(alive[inverse])


def choose_regions(spec, unit, frontier, view, names, uncertainty):
  pc = spec.proposal
  basins = np.full(len(unit), -1, int)
  chosen = []
  roles = {}
  if not len(frontier):
    return chosen, roles, basins
  cluster = KMeans(
      n_clusters=min(pc.clusters, len(frontier)), random_state=pc.seed,
      n_init=10
  ).fit(unit[frontier])
  basins[frontier] = cluster.labels_

  def add(i, role):
    if i in chosen or (
        chosen and cdist(unit[[i]], unit[chosen]).min() < pc.minProposalDistance
    ):
      return False
    chosen.append(int(i))
    roles[int(i)] = role
    return True

  for basin in sorted(set(basins[frontier])):
    if len(chosen) >= pc.count:
      break
    members = frontier[basins[frontier] == basin]
    for i in sorted(members, key=lambda i: (uncertainty[i], int(i))):
      if add(i, "basin_consensus"):
        break
  role_values, role_names = rule_scores(pc.roles, view, names)
  for j, name in enumerate(role_names):
    if len(chosen) >= pc.count - pc.explorationCount:
      break
    for i in sorted(
        frontier, key=lambda i: (-role_values[i, j], uncertainty[i], int(i))
    ):
      if add(i, name):
        break
  if pc.explorationCount:
    for i in sorted(frontier, key=lambda i: (-uncertainty[i], int(i))):
      if len(chosen) >= pc.count:
        break
      if (
          add(i, "model_disagreement")
          and sum(r == "model_disagreement" for r in roles.values())
          >= pc.explorationCount
      ):
        break
  while len(chosen) < pc.count:
    available = [i for i in frontier if i not in chosen]
    if not available:
      break
    available.sort(
        key=lambda i: (
          -cdist(unit[[i]], unit[chosen]).min() if chosen else 0,
          int(i),
        )
    )
    if not add(available[0], "diverse_tradeoff"):
      break
  return chosen, roles, basins


def prepare_benchmarks(spec, root, output, selected, *, selection_note=None):
  """Populate the existing harness JSON schema using task-declared bindings and controls."""
  from .surrogate_tournament import write, sha

  bp = spec.proposal.benchmark
  if bp is None or not selected:
    return None
  parent = output / bp.subdirectory
  if parent.exists():
    raise ValueError("benchmark preparation output already exists")
  parent.mkdir(parents=True)
  template_path = root / bp.templateHarness
  harness = json.loads(template_path.read_text())
  template = harness["trials"][0]
  arms = []
  for control in bp.controls:
    function = (
      json.loads((root / control["functionArtifact"]).read_text())
      if control.get("functionArtifact")
      else control.get("function")
    )
    arms.append(dict(id=control["id"], function=function, control=True))
  arms += [
    dict(id=p["id"], function=p["function"], theta=p["theta"], control=False,
         **{k: p[k] for k in ('family', 'role', 'pairedTo', 'alsoPairedTo') if
            k in p})
    for p in selected
  ]
  if len({a["id"] for a in arms}) != len(arms):
    raise ValueError("duplicate benchmark arm")
  trials = []
  for index, (fixture, arm) in enumerate(
      (f, a) for f in spec.dataset.fixtures for a in arms
  ):
    for block in range(bp.blocks):
      trial = deepcopy(template)
      cfg = trial["calibrationConfig"]
      for path, key in bp.fixtureBindings.items():
        pointer(cfg, path, fixture[key], True)
      pointer(cfg, bp.functionConfigPath, arm["function"], True)
      trial.update(
          id=f"{spec.id}-{index}-pass-{block}",
          origin=dict(
              type="SWEEP",
              sourceId=spec.id,
              seed=spec.proposal.seed,
              candidateIndex=index,
              sampleIndex=block,
          ),
          labels=dict(
              policyId=arm["id"],
              workloadId=fixture["workloadId"],
              workloadClass=fixture["workloadClass"],
          ),
      )
      if trial.get("forks") != 1:
        raise ValueError(
            "benchmark template must use one independent JVM per trial"
        )
      trials.append(trial)
  harness.update(id=spec.id, trials=trials)
  harness["runOptions"].update(balancedTrialOrder=True, repeatCount=1)
  harness["artifacts"].update(
      outputDirectory=bp.runDirectory,
      retainExpandedConfig=True,
      retainRawBenchmarkOutput=True,
  )
  write(parent / "harness.json", harness)
  for arm in arms:
    # Opaque runtime configs are emitted, never new Java model classes.
    write(
        parent / "policies" / f"{arm['id']}.json",
        {
          bp.functionConfigPath.lstrip("/"): arm["function"],
          **spec.dataset.fixedControl,
        },
    )
  reference = {}
  if spec.dataset.referenceIdentity:
    path = root / spec.dataset.referenceIdentity
    if path.exists():
      reference = json.loads(path.read_text())
    elif spec.dataset.archive:
      from .run_archive import read_file

      reference = json.loads(
          read_file(root / spec.dataset.archive, spec.dataset.referenceIdentity)
      )
    else:
      raise FileNotFoundError(path)
  manifest = dict(
      schemaVersion=1,
      id=spec.id,
      status="prepared_not_executed",
      arms=arms,
      fixtures=list(spec.dataset.fixtures),
      blocks=bp.blocks,
      expectedForks=len(trials),
      windowsPerFork=template["iterations"],
      templateHarness=bp.templateHarness,
      templateSha256=sha(template_path),
      runtimeReference=reference,
      benchmarkOutputDirectory=bp.runDirectory,
      productionAcceptance="Actual matched JVM throughput; predictions are not acceptance evidence",
      topologies=reference.get("topologies", {}),
      stages={
        bp.stage: dict(
            harness="harness.json",
            fixtures=list(spec.dataset.fixtures),
            expectedForks=len(trials),
            windowsPerFork=template["iterations"],
            outputDirectory=bp.runDirectory,
            evidenceDirectory=bp.runDirectory + "-evidence",
            requiredReviews=[],
        )
      },
  )
  write(parent / "candidate_manifest.json", manifest)
  write(
      parent / "collection.json",
      dict(
          harness="harness.json",
          manifest="candidate_manifest.json",
          expectedForks=len(trials),
          replicationUnit="JVM fork",
          retainAllWindows=True,
          retainSlowForks=True,
          referenceSystems=["OFF", "same_campaign_center"],
          responses=[r.model_dump() for r in spec.responses],
          workloadClasses="report separately",
          automaticExecution=False,
          sameBaseComparisons=[dict(fullPolicyId=full,
                                    zeroPolicyId=p['id'])
                               for p in selected if 'pairedTo' in p
                               for full in
                               [p['pairedTo'], *p.get('alsoPairedTo', [])]],
      ),
  )
  from .cache_timing_policy import freeze

  parent_display = (
    str(parent.relative_to(root)) if parent.is_relative_to(root) else str(
      parent)
  )
  (parent / "HANDOFF.md").write_text(
      "# Bounded surrogate proposal benchmark handoff\n\n"
      "Prepared only. Execute after review; no JMH run was performed during preparation.\n\n"
      f"Arms: {len(arms)}; workloads: {len(spec.dataset.fixtures)}; independent blocks: {bp.blocks}; "
      f"JVM forks: {len(trials)}. Keep every fork and every measurement window. "
      "Do not rerun slow outcomes. Scarce and plentiful results remain separate.\n\n"
      + (
        selection_note if selection_note is not None else "The benchmark is the oracle. Predictive soft risks and unresolved outputs are not "
      "production acceptance decisions. See the parent proposals.json and output_reliability.json. "
      "When enabled, known_region_coverage.json records measured neighborhood coverage and "
                                                          "the automatic selection changes; broad basin membership does not replace local distance.\n\n")
      +
      "From the repository root, with the tournament Python environment active:\n\n"
      "```bash\nunset JAVA_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS\n"
      'export PYTHONPATH="$PWD/python/pareto-weight-calibration/src"\n'
      f"python -m pareto_weight_calibration.cache_timing_confirmation check --handoff {parent_display} --stage {bp.stage}\n"
      "```\n\nAfter review, the existing runner commands are:\n\n```bash\n"
      f"python -m pareto_weight_calibration.cache_timing_confirmation run --handoff {parent_display} --stage {bp.stage}\n"
      f"python -m pareto_weight_calibration.cache_timing_confirmation collect --handoff {parent_display} --stage {bp.stage}\n"
      "```\n\nThe run command assembles with Mise, rechecks the frozen sources/topology, records "
      "launch identity, and invokes the existing balanced calibration harness. Collection retains "
      "forks/windows, raw throughput, OFF-relative returns, and scarcity/plentiful summaries. "
      "The exact-center arm also permits same-campaign center-relative analysis through the historical adapter.\n\n"
      "Stop on source/hash/topology mismatch. Do not edit a frozen lock to bypass a failure. "
      "Finalize source changes before regenerating a reviewed version. Do not change runtime "
      "defaults, participation, bounds or coefficients during execution.\n"
  )
  freeze(parent)
  return dict(
      directory=bp.subdirectory,
      arms=len(arms),
      blocks=bp.blocks,
      workloads=len(spec.dataset.fixtures),
      jvmCount=len(trials),
  )


def search(
    spec,
    root,
    data,
    result,
    names,
    output,
    unit,
    theta,
    prediction,
    disagreement,
    allpred,
    members,
    functions,
    support_bad,
):
  from .surrogate_tournament import write, tsv, clean

  pc = spec.proposal
  authority = result["proposalAuthority"]
  view = policy_view(spec, prediction, disagreement, names, authority)
  old = policy_view(spec, prediction, disagreement, names, authority, old=True)
  history = history_exclusions(spec, data["rows"], unit)
  base = history["eligible"] & ~support_bad
  scores, score_names = search_scores(spec, view, names)
  usable = np.isfinite(scores).all(axis=1)
  base &= usable
  unconstrained = np.flatnonzero(base)
  unconstrained = unconstrained[nondominated(scores[unconstrained])]
  valid = base & view["eligible"]
  ids = np.flatnonzero(valid)
  frontier = ids[nondominated(scores[ids])]
  useful = [j for j, n in enumerate(names) if authority[n]["directionUseful"]]
  spread = (
    disagreement[:, useful]
    if useful and pc.reliability.disagreementUsefulOnly
    else disagreement
  )
  uncertainty = np.max(np.where(np.isfinite(spread), spread, 0.0), axis=1)
  if pc.maxDisagreementLog is not None:
    # Optional hard disagreement cap is independent of throughput prediction authority.
    valid &= uncertainty <= pc.maxDisagreementLog
    ids = np.flatnonzero(valid)
    frontier = ids[nondominated(scores[ids])]
  chosen, roles, basins = choose_regions(
      spec, unit, frontier, view, names, uncertainty
  )
  from .known_regions import ensure_coverage
  role_values, _ = rule_scores(pc.roles, view, names)
  direction = role_values[:, 0] if role_values.shape[1] else np.zeros(len(unit))
  chosen, roles, coverage = ensure_coverage(
      spec, data, unit, valid, frontier, chosen, roles, basins, uncertainty,
      direction)
  if coverage is not None:
    for region in coverage['regions']:
      region['selectedProposalIds'] = [f"{spec.id}-round{pc.round}-sample{i}"
                                       for i in region['selectedProposals']]
    write(output / "known_region_coverage.json", clean(coverage))
    tsv(output / "known_region_coverage.tsv", coverage["regions"])
    write(output / "known_region_policy.json",
          pc.knownRegionCoverage.model_dump())
  classes = {n: a["reliabilityClass"] for n, a in authority.items()}
  selected = []
  consensus = []
  for i in chosen:
    responses = {}
    for j, name in enumerate(names):
      responses[name] = dict(
          selectedPredictionLog=float(prediction[i, j]),
          disagreementLog=float(disagreement[i, j]),
          reliabilityClass=classes[name],
          contribution=view["treatments"][name],
          softPenalty=(
            float(view["risk"][name][i]) if name in view["risk"] else None
          ),
          models={m: float(allpred[m][i, j]) for m in members.get(name, [])},
          directionSource=view["directionSources"][name],
      )
      consensus.append(dict(sampleIndex=int(i), output=name, **responses[name]))
    selected.append(
        dict(
            id=f"{spec.id}-round{pc.round}-sample{i}",
            sampleIndex=int(i),
            theta=theta[i].tolist(),
            parameters=dict(
                zip([p.name for p in spec.parameters], theta[i].tolist())
            ),
            normalizedCoordinates=unit[i].tolist(),
            basin=int(basins[i]),
            role=roles[i],
            nearestMeasuredTheta=history["nearest"][i],
            distanceToMeasured=float(history["distance"][i]),
            distanceToRepeatedBad=float(history["distanceToRepeatedBad"][i]),
            predictions=dict(zip(names, prediction[i].tolist())),
            modelDisagreement=dict(zip(names, disagreement[i].tolist())),
            reliability=classes,
            contribution=view["treatments"],
            unresolvedGuardrails=[
              n for n in view["unresolved"] if n in pc.floorPercent
            ],
            responses=responses,
            function=functions.get(i),
            predictionOnly=True,
            requiresMatchedBenchmark=True,
        )
    )
  dense = []
  for i in range(len(unit)):
    status = (
      "eligible"
      if valid[i]
      else (
        "hard_predicted_floor"
        if base[i]
        else (
          "support_filter"
          if support_bad[i]
          else (
            "measured_history_proximity"
            if not history["eligible"][i]
            else "unusable_prediction"
          )
        )
      )
    )
    dense.append(
        dict(
            sampleIndex=i,
            status=status,
            basin=int(basins[i]),
            theta=theta[i].tolist(),
            nearestMeasuredTheta=history["nearest"][i],
            distanceToMeasured=history["distance"][i],
            distanceToRepeatedBad=history["distanceToRepeatedBad"][i],
            predictedLogReturns=dict(zip(names, prediction[i].tolist())),
            disagreementLog=dict(zip(names, disagreement[i].tolist())),
            reliability=classes,
            contribution=view["treatments"],
            softRisks={n: v[i] for n, v in view["risk"].items()},
            failedHardFloors=[n for n, v in view["failures"].items() if v[i]],
        )
    )
  tsv(output / "dense_predictions.tsv", dense)
  tsv(output / "unconstrained_pareto.tsv", [dense[i] for i in unconstrained])
  tsv(output / "pareto_frontier.tsv", [dense[i] for i in frontier])
  tsv(output / "proposal_consensus.tsv", consensus)
  tsv(
      output / "model_consensus.tsv",
      [
        dict(
            output=n,
            members=m,
            reliabilityClass=classes[n],
            contribution=view["treatments"][n],
        )
        for n, m in members.items()
      ],
  )
  basin_report = [
    dict(
        id=int(b),
        frontierMembers=int(np.sum(basins == b)),
        representatives=[p for p in selected if p["basin"] == b],
    )
    for b in sorted(set(basins[frontier]))
  ]
  write(
      output / "basins.json",
      clean(
          dict(
              basins=basin_report,
              interpretation="Separated normalized-parameter clusters of predicted tradeoffs; not measured physical basins",
          )
      ),
  )
  known = []
  for row in result.get("retrospective", {}).get("knownPolicyRecovery", []):
    theta_row = next(r for r in data["rows"] if r["thetaId"] == row["thetaId"])
    from .parameter_tuning import normalize

    center = np.array(normalize(spec.parameters, theta_row["theta"]))
    distance = np.linalg.norm(unit - center, axis=1)
    nearby = distance <= pc.retrospective.neighborhoodRadius
    known.append(
        dict(
            **row,
            neighborhoodRadius=pc.retrospective.neighborhoodRadius,
            denseNeighbors=int(nearby.sum()),
            eligibleNeighbors=int(np.sum(nearby & valid)),
            paretoNeighbors=int(np.sum(nearby[frontier])),
            proposedNeighbors=[int(i) for i in chosen if nearby[i]],
            nearestProposalDistance=(
              float(min(distance[chosen])) if chosen else None
            ),
        )
    )
  write(output / "known_region_recovery.json", clean(known))
  manifest = dict(
      schemaVersion=2,
      round=pc.round,
      parentDataset=pc.parentDataset,
      taskHash=digest(spec.model_dump()),
      bounds=[p.model_dump() for p in spec.parameters],
      searched=len(unit),
      eligible=len(ids),
      paretoSize=len(frontier),
      unconstrainedParetoSize=len(unconstrained),
      oldHardEligible=int(np.sum(base & old["eligible"])),
      supportRejected=int(support_bad.sum()),
      historyExcluded=int(np.sum(~history["eligible"])),
      reliableHardRejected=int(np.sum(base & ~view["eligible"])),
      dominatedEligible=len(ids) - len(frontier),
      authorityCounts=dict(Counter(classes.values())),
      activeBasins=[
        dict(id=b["id"], members=b["frontierMembers"]) for b in basin_report
      ],
      selected=selected,
      stopReason=(
        None
        if selected
        else "No region remained; no automatic threshold relaxation"
      ),
      directionSources=view["directionSources"],
      rankingObjectives=score_names,
      repeatedBadHistory=history["repeatedBad"],
      knownRegionCoverage=coverage,
      benchmarkExecution=False,
      uncertaintyMeaning="Model disagreement, not a confidence interval",
      acceptance="Benchmark is the oracle; unresolved is neither safe nor failed",
  )
  manifest["benchmarkPreparation"] = prepare_benchmarks(spec, root, output,
                                                        selected)
  write(output / "proposals.json", clean(manifest))
  return manifest
