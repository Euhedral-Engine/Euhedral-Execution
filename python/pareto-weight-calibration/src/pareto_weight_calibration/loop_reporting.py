"""Operator views of existing measured decisions, and post-UPDATE raw-attempt cleanup."""
from copy import deepcopy
from pathlib import Path
import csv
import io
import json
import math
import os
import shutil
import statistics
from .loop_data import compatible_families


def atomic_text(path, text):
  path.parent.mkdir(parents=True, exist_ok=True)
  tmp = path.with_name(path.name + '.tmp')
  with tmp.open('w') as stream:
    stream.write(text)
    stream.flush()
    os.fsync(stream.fileno())
  os.replace(tmp, path)


def duration(seconds):
  if seconds is None:
    return 'estimating'
  seconds = max(0, seconds)
  if seconds < 60:
    return f'{seconds:.1f}s'
  minutes, seconds = divmod(int(seconds), 60)
  hours, minutes = divmod(minutes, 60)
  return f'{hours}h {minutes:02d}m' if hours else f'{minutes}m {seconds:02d}s'


class Progress:
  def __init__(self, spec, state):
    self.spec = spec
    self.state = state

  def phase(self, name):
    timings = self.state.get('progress', {}).get('phaseSeconds', {}).get(name,
                                                                         [])
    eta = f' | ETA ~{duration(statistics.mean(timings))} (prior phase durations)' if timings else ' | ETA estimating'
    print(
      f"Round {self.state['round'] + 1}/{self.spec.budgets.rounds}: {name}{eta}",
      flush=True)

  def phase_done(self, name, elapsed):
    self.state.setdefault('progress', {}).setdefault('phaseSeconds',
                                                     {}).setdefault(name,
                                                                    []).append(
      elapsed)
    print(f'  {name} complete in {duration(elapsed)}', flush=True)

  def detail(self, message):
    print(f'  {message}', flush=True)

  def trial(self, index, trial, status, attempt=None, elapsed=None,
      reason=None):
    trials = self.state['trials']
    prefix = f"[Round {self.state['round'] + 1}/{self.spec.budgets.rounds}] [Trial {index}/{len(trials)}]"
    fields = [prefix, status]
    if status in ['START', 'RETRY', 'RUNNING']:
      fields += [f"policy={trial['arm']['policyId']}",
                 f"workload={trial['fixture']['workloadId']}",
                 f"block={trial['blockId']}"]
    if attempt is not None:
      fields.append(
        f"attempt={attempt['number'] + 1}/{self.spec.budgets.attemptsPerTrial}")
    if elapsed is not None:
      fields.append(duration(elapsed))
    if reason:
      fields.append(': ' + ' '.join(str(reason).split())[:240])
    samples = self.state.get('progress', {}).get('trialSeconds', [])
    if samples:
      mean = statistics.mean(samples)
      pending = sum(t['status'] != 'COMPLETE' for t in trials)
      current_elapsed = (elapsed or 0) if status == 'RUNNING' else 0
      eta = max(0, mean * pending - current_elapsed)
      fields.append(f'| round ETA ~{duration(eta)}')
    else:
      fields.append('| round ETA estimating')
    print(' '.join(fields), flush=True)

  def attempt_done(self, elapsed):
    self.state.setdefault('progress', {}).setdefault('trialSeconds', []).append(
      elapsed)


def startup(spec, summary, state=None, historical_forks=None, resume=False):
  resources = summary['resources']
  lines = [f'\n{spec.id}: automatic parameter tuner',
           f'  rounds: {spec.budgets.rounds} | beam width: {spec.budgets.beamWidth}',
           f"  new policies/round: {summary['newPoliciesPerRound']} (guided {spec.search.guided}, local random {spec.search.localRandom}, global random {spec.search.globalRandom})",
           f"  fixtures: {len(spec.fixtures)} | blocks: {spec.benchmark.blocks}",
           f"  projected forks (excluding retries): {summary['projectedMaximumJvmForks']}",
           f"  current historical/session forks: {historical_forks if historical_forks is not None else 'pending import'}",
           f'  search: Sobol power {spec.search.power}, max power {spec.search.maxPower}',
           f"  execution: device={resources['requestedDevice']} fit workers={resources['fitWorkers']} predict workers={resources['predictWorkers']}",
           f"  output: {summary['output']}"]
  if spec.preference.objective == 'scarce':
    lines[0] = '\nCACHE scarce-policy tuner'
    lines[1:1] = [
      '  objective: scarce-only | plentiful: bypass learned CACHE withdrawal',
      f'  active beam: {spec.budgets.beamWidth} | elite archive: {spec.budgets.eliteSize}',
      f'  OFF drift sentinel: {spec.benchmark.offSentinel} | 1 fork/round',
      '  candidate returns: matched same-round incumbent; sentinel is not a full OFF baseline']
  if resume and state:
    trials = state.get('trials', []) if state['phase'] in ['BENCHMARK',
                                                           'COLLECT',
                                                           'UPDATE'] else []
    lines += [
      f"  resuming round: {min(state['round'] + 1, spec.budgets.rounds)}/{spec.budgets.rounds} | phase: {state['phase']}",
      f"  completed trials in current round: {sum(t['status'] == 'COMPLETE' for t in trials)}/{len(trials)}",
      '  current incumbent(s): ' + ', '.join(
          r['policyId'] for r in state.get('regions', [])),
      f"  remaining rounds: {max(0, spec.budgets.rounds - state['round'])} | attempts recorded: {state['attempts']}"]
  print('\n'.join(lines), flush=True)


def policy_view(spec, row, region=None):
  """Convert the optimizer's already measured log returns; never rescore or pool campaigns."""
  responses = {r.name: r for r in spec.responses}
  scarce_workloads = sorted({w for n in spec.preference.primaryTargets for w in
                             responses[n].workloads})
  plentiful_workloads = sorted(
      {w for n in spec.preference.guardrailTargets for w in
       responses[n].workloads})
  pct = lambda value: 100 * math.expm1(value)

  def worst(workloads):
    if not workloads: return None
    name = min(workloads, key=lambda w: (row['workloadReturns'][w], w))
    return dict(workloadId=name, percent=pct(row['workloadReturns'][name]))

  def broad(workloads):
    return pct(statistics.mean(
        row['workloadReturns'][w] for w in workloads)) if workloads else None

  # Roles identify topology aggregates; no R7/R15/R23 or parameter-width special cases.
  plentiful_names = [r.name for r in spec.responses if r.role == 'plentiful']
  if not plentiful_names:
    plentiful_names = list(spec.preference.guardrailTargets)
  families = compatible_families(spec, row['function'])
  family = next(
      f for f in spec.families if f.id == region['family']) if region else next(
      f for f in spec.families if f.id in families and (
            not row.get('searchFamily') or f.id == row['searchFamily']))
  theta = families[family.id]
  return dict(policyId=row['policyId'], family=family.id,
              campaignId=row['campaignId'],
              referencePolicyId=row.get('referencePolicyId', 'POLICY_OFF'),
              referenceKind=row.get('referenceKind',
                                    'same-campaign matched OFF'),
              measuredScore=row['score'], measuredObjectives=row['objectives'],
              scarceTopologyPercent={n: pct(row['targets'][n]) for n in
                                     spec.preference.topologyTargets},
              plentifulTopologyPercent={n: pct(row['targets'][n]) for n in
                                        plentiful_names},
              broadScarcePercent=broad(scarce_workloads),
              broadPlentifulPercent=broad(plentiful_workloads),
              positiveScarceWorkloads=sum(
                  row['workloadReturns'][w] > 0 for w in scarce_workloads),
              scarceWorkloadCount=len(scarce_workloads),
              worstScarce=worst(scarce_workloads),
              worstPlentiful=worst(plentiful_workloads), theta=theta,
              activeParameters={p.name: v for p, v in
                                zip(family.parameters, theta)},
              searchRadius=region['radius'] if region else None,
              function=row['function'])


def round_summary(spec, index, campaign, ranked, previous, accepted,
    next_regions, improved, stagnation):
  by_id = {r['policyId']: r for r in ranked}
  previous_best = max(previous, key=lambda r: by_id[r['policyId']]['score'])
  current = max(accepted, key=lambda r: by_id[r['policyId']]['score'])
  winner = ranked[0]
  winner_region = next(
      (r for r in accepted if r['policyId'] == winner['policyId']), None)
  delta = winner['score'] - by_id[previous_best['policyId']]['score']
  answer = policy_view(spec, by_id[current['policyId']], current)
  answer.update(round=index + 1, acceptedIncumbent=True,
                previousIncumbentId=previous_best['policyId'],
                improvementVsPreviousIncumbent=by_id[current['policyId']][
                                                 'score'] -
                                               by_id[previous_best['policyId']][
                                                 'score'],
                improvementMetric='same-round preference score difference',
                incumbentStatus='newly accepted' if current['policyId'] not in {
                  r['policyId'] for r in previous} else 'retained')
  return dict(round=index + 1, roundIndex=index, rounds=spec.budgets.rounds,
              campaignId=campaign,
              roundBest=dict(policy_view(spec, winner, winner_region),
                             acceptedAsIncumbent=winner_region is not None),
              currentIncumbent=answer, previousIncumbent=dict(
      policy_view(spec, by_id[previous_best['policyId']], previous_best)),
              roundBestDeltaVsPreviousIncumbent=delta, improved=improved,
              stagnation=stagnation,
              previousSearchRegions=deepcopy(previous),
              acceptedRegions=deepcopy(accepted),
              nextSearchRegions=deepcopy(next_regions),
              updateComplete=True,
              cleanup=dict(status='pending',
                           successfulTrialDirectoriesRemoved=0,
                           failedAttemptDirectoriesRetained=0, bytesRemoved=0,
                           attempts=[], errors=[]))


def summary_text(summary):
  best = summary['roundBest'];
  current = summary['currentIncumbent'];
  previous = summary['previousIncumbent']
  yes = lambda value: 'YES' if value else 'NO'
  lines = ['=' * 60, f"ROUND {summary['round']}/{summary['rounds']} COMPLETE",
           '=' * 60,
           'ROUND BEST', f"  policy: {best['policyId']}",
           f"  family: {best['family']}",
           f"  accepted as incumbent: {yes(best['acceptedAsIncumbent'])}"]

  def show(view):
    for label, key, broad in [
      ('Scarce', 'scarceTopologyPercent', 'broadScarcePercent'),
      ('Plentiful', 'plentifulTopologyPercent', 'broadPlentifulPercent')]:
      if view[broad] is None: continue
      lines.append(
        f"  {label} throughput vs same-round {view.get('referencePolicyId', 'POLICY_OFF')}:")
      for name, value in view[key].items(): lines.append(
        f'    {name}: {value:+.2f}%')
      lines.append(f"    Broad: {view[broad]:+.2f}%")
    lines.extend(
        [f"  scarce breadth: {view['positiveScarceWorkloads']}/{view['scarceWorkloadCount']} workloads positive",
         f"  worst scarce: {view['worstScarce']['workloadId']} {view['worstScarce']['percent']:+.2f}%",
         *(
           [f"  worst plentiful: {view['worstPlentiful']['workloadId']} {view['worstPlentiful']['percent']:+.2f}%"] if
           view['worstPlentiful'] else [])])

  show(best)
  lines += ['CURRENT INCUMBENT (accepted measured policy)',
            f"  policy: {current['policyId']} | family: {current['family']}"]
  if current['policyId'] != best['policyId']: show(current)
  lines += [
    f"  previous incumbent: {previous['policyId']} | same-round score: {previous['measuredScore']:.6f}",
    f"  round-best score delta vs previous: {summary['roundBestDeltaVsPreviousIncumbent']:+.6f}",
    f"  accepted score: {current['measuredScore']:.6f}",
    f"  radius: {previous['searchRadius']:.6g} -> {current['searchRadius']:.6g}",
    f"  improved: {yes(summary['improved'])} | stagnation: {summary['stagnation']} rounds",
    '  exact active parameters (accepted incumbent):']
  lines.extend(f'    {name}: {value!r}' for name, value in
               current['activeParameters'].items())
  lines.append('  exact config: current-best-config.json')
  if summary.get('searchUpdates'):
    lines.append('SEARCH UPDATE')
    for event in summary['searchUpdates']:
      lines.extend(
          [f"  parent: {event['parentPolicyId']} | family: {event['parentFamily']}",
           f"  candidate: {event['candidatePolicyId']} | search family: {event['searchFamily']}",
           f"  distance: {event['normalizedDistanceFromParent']} | edge fraction: {event['normalizedEdgeFraction']}",
           f"  decision: {event['decision']} | radius: {event['oldRadius']} -> {event['newRadius']}",
           '  reason: ' + event['reason']])
  if summary.get('eliteArchive'):
    lines.append(
      'ELITE ARCHIVE (measured network estimates, not fresh OFF comparisons)')
    lines.extend('  ' + e['policyId'] for e in summary['eliteArchive'])
  if summary.get('offSentinel'):
    sentinel = summary['offSentinel']
    lines.append(
      f"OFF drift sentinel: {sentinel['workloadId']} {sentinel['throughput']:.3f} executions/s | change from previous sentinel: {sentinel['changePercent']}")
  lines.append('  next search centers: ' + ', '.join(
      f"{r['policyId']} ({r['family']}, radius={r['radius']:.6g})" for r in
      summary['nextSearchRegions']))
  if summary['nextSearchRegions'] != summary['acceptedRegions']:
    lines.append(
      '  alternate-region restart; accepted measured answer retained')
  if summary['round'] >= summary['rounds']:
    lines.append('Next: round budget complete.')
  else:
    lines.append(
      f"Next: proceeding to round {summary['round'] + 1}/{summary['rounds']}.")
  c = summary['cleanup']
  lines.append(
    f"Cleanup: {c['status']} | successful attempts removed: {c['successfulTrialDirectoriesRemoved']} | failed/interrupted retained: {c['failedAttemptDirectoriesRetained']} | bytes removed: {c['bytesRemoved']}")
  lines.extend('  cleanup error: ' + message for message in c.get('errors', []))
  return '\n'.join(lines) + '\n'


def persist_summary(directory, summary, atomic):
  atomic(directory / 'round-summary.json', summary)
  atomic_text(directory / 'round-summary.txt', summary_text(summary))


def publish_current(output, summary, atomic):
  atomic(output / 'current-best-config.json',
         summary['currentIncumbent']['function'])
  atomic(output / 'current-best.json', summary['currentIncumbent'])


def leaderboard(spec, output):
  rows = []
  for path in sorted(output.glob('round-*/round-summary.json')):
    summary = json.loads(path.read_text())
    ranking = json.loads((path.parent / 'measured-ranking.json').read_text())
    incumbents = {r['policyId'] for r in summary['acceptedRegions']}
    for measured in ranking:
      view = policy_view(spec, measured)
      row = dict(round=summary['round'], campaignId=measured['campaignId'],
                 policyId=view['policyId'], family=view['family'],
                 incumbent=view['policyId'] in incumbents,
                 roundWinner=view['policyId'] == summary['roundBest'][
                   'policyId'],
                 score=view['measuredScore'],
                 broadScarcePercent=view['broadScarcePercent'],
                 broadPlentifulPercent=view['broadPlentifulPercent'],
                 positiveScarceCount=view['positiveScarceWorkloads'],
                 worstScarcePercent=view['worstScarce']['percent'],
                 worstPlentifulPercent=view['worstPlentiful']['percent'] if
                 view['worstPlentiful'] else None,
                 referencePolicyId=view['referencePolicyId'])
      row.update({name + 'Percent': value for name, value in
                  {**view['scarceTopologyPercent'],
                   **view['plentifulTopologyPercent']}.items()})
      rows.append(row)
  if rows:
    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=list(rows[0]), delimiter='\t')
    writer.writeheader();
    writer.writerows(rows)
    atomic_text(output / 'leaderboard.tsv', buffer.getvalue())


def cleanup_round(spec, directory, summary, records, committed_round, atomic):
  """Only prune owned successful attempts after durable UPDATE and complete compact evidence."""
  if committed_round <= summary['roundIndex']:
    return summary
  required = ['forks.json', 'throughput.tsv', 'measured-ranking.json',
              'round-summary.json', 'round-summary.txt', 'update.json',
              'trial-plan.json', 'arms.json', 'proposals.json']
  status = summary['cleanup']
  try:
    if not summary.get('updateComplete') or any(
        not (directory / name).is_file() for name in required):
      raise ValueError(
        'round compact evidence or committed UPDATE is incomplete')
    plan = json.loads((directory / 'trial-plan.json').read_text())['trials']
    forks = json.loads((directory / 'forks.json').read_text())
    durable = {r['rowId']: r for r in records}
    expected = {summary['campaignId'] + '/' + t['id']: t for t in plan}
    if len(expected) != len(plan) or len(forks) != len(plan) or {r['rowId'] for
                                                                 r in
                                                                 forks} != set(
        expected):
      raise ValueError('fork inventory does not match round trial plan')
    for row in forks:
      trial = expected[row['rowId']]
      if trial['status'] != 'COMPLETE' or not trial['attempts'] or \
          trial['attempts'][-1]['status'] != 'COMPLETE' or durable.get(
          row['rowId']) != row:
        raise ValueError('trial not complete or exact fork missing from SQLite')
      if row['policyId'] != trial['arm']['policyId'] or row['workloadId'] != \
          trial['fixture']['workloadId'] or row['blockId'] != trial[
        'blockId'] or row['function'] != trial['arm']['function']:
        raise ValueError('fork arm/workload does not match plan')
      if len(row['windows']) != spec.benchmark.harness['trials'][0][
        'iterations'] or not all(
          math.isfinite(x) and x > 0 for x in row['windows']):
        raise ValueError('invalid retained measurement windows')
      if not math.isfinite(row['rawThroughput']) or not math.isclose(
          row['rawThroughput'], statistics.mean(row['windows']), rel_tol=1e-12):
        raise ValueError(
          'fork mean does not match retained measurement windows')
    if not spec.cleanup.successfulRawTrials:
      status['failedAttemptDirectoriesRetained'] = sum(
          Path(a['harness']).parent.exists() for t in plan for a in
          t['attempts'] if a['status'] != 'COMPLETE')
      status['status'] = 'disabled'
      persist_summary(directory, summary, atomic)
      return summary
    entries = {e['path']: e for e in status['attempts']}
    retained = []
    eligible_paths = set()
    for trial in plan:
      for attempt in trial['attempts']:
        folder = Path(attempt['harness']).parent
        if attempt['status'] != 'COMPLETE' and spec.cleanup.retainFailedTrials:
          retained.append(folder)
          continue
        if folder.is_symlink() or not folder.resolve().is_relative_to(
            (directory / 'trials').resolve()):
          raise ValueError('attempt directory escapes owned round trials')
        key = str(folder)
        eligible_paths.add(key)
        if key not in entries:
          size = sum(p.lstat().st_size for p in folder.rglob('*') if
                     p.is_file() and not p.is_symlink()) if folder.exists() else 0
          entries[key] = dict(path=key, bytes=size, status='pending',
                              successful=attempt['status'] == 'COMPLETE')
    if not set(entries) <= eligible_paths:
      raise ValueError(
        'cleanup journal contains an attempt outside the eligible trial plan')
    status['attempts'] = list(entries.values())
    status['errors'] = []
    # Persist deletion intent before touching raw data, so retries can account for an interrupted removal.
    persist_summary(directory, summary, atomic)
    for entry in status['attempts']:
      if entry['status'] == 'removed': continue
      try:
        folder = Path(entry['path'])
        if folder.is_symlink() or not folder.resolve().is_relative_to(
            (directory / 'trials').resolve()):
          raise OSError('cleanup target is outside owned attempt directories')
        if folder.exists(): shutil.rmtree(folder)
        entry['status'] = 'removed'
      except OSError as error:
        status['errors'].append(f"{entry['path']}: {error}")
      persist_summary(directory, summary, atomic)
    status['successfulTrialDirectoriesRemoved'] = sum(
        e['successful'] and e['status'] == 'removed' for e in
        status['attempts'])
    status['failedAttemptDirectoriesRetained'] = sum(
        p.exists() for p in retained)
    status['bytesRemoved'] = sum(
        e['bytes'] for e in status['attempts'] if e['status'] == 'removed')
    status['status'] = 'complete' if not status['errors'] else 'partial'
  except (OSError, ValueError, KeyError) as error:
    status['status'] = 'blocked'
    status['errors'] = [str(error)]
  persist_summary(directory, summary, atomic)
  return summary
