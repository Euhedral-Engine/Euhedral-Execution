"""JSON contract for finite, resumable measured parameter optimization."""
from typing import Literal
from pydantic import Field, model_validator
from .training_spec import SpecModel
from .surrogate_spec import ModelConfig, Response, ValidationConfig
from .tuning_spec import Parameter
from .execution_resources import ExecutionConfig


class LoopFamily(SpecModel):
  id: str
  parameters: tuple[Parameter, ...]
  template: dict

  @model_validator(mode='after')
  def valid(self):
    from .parameter_tuning import construct
    if not self.parameters or len({p.path for p in self.parameters}) != len(
        self.parameters) or len({p.name for p in self.parameters}) != len(
        self.parameters):
      raise ValueError('distinct active parameter paths required')
    construct(self.parameters, self.template,
              [p.center for p in self.parameters])
    return self


class LoopSearch(SpecModel):
  power: int = Field(default=24, ge=3, le=28)
  maxPower: int = Field(default=26, ge=3, le=28)
  growBelowSeconds: float = Field(default=30, ge=0)
  growOnImprovementLog: float = Field(default=.01, ge=0)
  reservoir: int = Field(default=1024, ge=8)
  maxBatchRows: int = Field(default=262144, ge=8)
  guided: int = Field(default=4, ge=0)
  localRandom: int = Field(default=2, ge=0)
  globalRandom: int = Field(default=1, ge=1)
  spacingFraction: float = Field(default=.12, gt=0, lt=1)
  historySpacingFraction: float = Field(default=.08, gt=0, lt=1)
  disagreementPenalty: float = Field(default=.1, ge=0)
  newBasinRadius: float = Field(default=.3, gt=0, le=1)
  stopPatience: int | None = Field(default=None, ge=1)
  initialRadius: float = Field(default=.3, gt=0, le=1)
  minimumRadius: float = Field(default=.002, gt=0, le=1)
  shrink: float = Field(default=.7, gt=0, lt=1)
  edgeFraction: float = Field(default=.8, gt=0, le=1)
  restartPatience: int = Field(default=2, ge=1)
  saveDense: bool = False

  @model_validator(mode='before')
  @classmethod
  def legacy_limits(cls, value):
    # Old session/task definitions remain loadable; rounds alone limit tuning.
    if isinstance(value, dict):
      return {k: v for k, v in value.items() if k != 'stagnationPatience'}
    return value


class LoopPreference(SpecModel):
  objective: Literal['balanced', 'scarce'] = 'balanced'
  primaryTargets: tuple[str, ...]
  topologyTargets: tuple[str, ...]
  guardrailTargets: tuple[str, ...] = ()
  positiveCapPercent: float = Field(default=25, gt=0)
  materialLossPercent: float = Field(default=-3, gt=-100, le=0)
  harmPenalty: float = Field(default=1, ge=0)
  minimumGainLog: float = Field(default=.002, ge=0)
  competitiveToleranceLog: float = Field(default=.02, ge=0)
  beamSeparation: float = Field(default=.15, gt=0)


class LoopBudgets(SpecModel):
  rounds: int = Field(default=4, ge=1)
  beamWidth: int = Field(default=2, ge=1)
  eliteSize: int = Field(default=5, ge=1)
  trialTimeoutSeconds: float = Field(default=240, gt=0)
  consecutiveFailures: int = Field(default=3, ge=1)
  attemptsPerTrial: int = Field(default=2, ge=1)

  @model_validator(mode='before')
  @classmethod
  def legacy_limits(cls, value):
    if isinstance(value, dict):
      return {k: v for k, v in value.items()
              if k not in {'jvmAttempts', 'wallHours'}}
    return value


class LoopBenchmark(SpecModel):
  adapter: Literal['cache_jmh', 'command'] = 'cache_jmh'
  offSentinel: str | None = None
  referenceMode: Literal['off', 'incumbent'] = 'off'
  harness: dict
  blocks: int = Field(default=2, ge=1)
  buildCommand: tuple[str, ...] = ('mise', 'exec', '--', 'gradle',
                                   ':benchmarks:assemble')
  command: tuple[str, ...] = ('mise', 'exec', '--', 'bash',
                              'benchmarks/build/bin/euhedral-calibration',
                              'run', '{harness}')
  functionPath: str = '/cacheTimingFunction'
  fixtureBindings: dict[str, str] = Field(default_factory=lambda: {
    'cpuSet': 'cpuSet', 'parallelSources': 'parallelSources',
    'workUnits': 'workUnits'})
  fixedControl: dict = Field(default_factory=lambda: {'cacheParkNs': 15000,
                                                      'contentionHalfLifeNanos': 1000000})
  referenceTemplate: dict | None = None
  # Allow exact source-treatment differences, never silently waive other runtime settings.
  historicalVariableKeys: tuple[str, ...] = ('cpuSet', 'parallelSources',
                                             'workUnits', 'cacheTimingFunction')
  requiredJvmOptions: tuple[str, ...] = (
    '-Deuhedral.calibration.throughputOnly=true',)


class LoopCleanup(SpecModel):
  successfulRawTrials: bool = True
  retainFailedTrials: bool = True


class LoopTask(SpecModel):
  schemaVersion: Literal[1] = 1
  kind: Literal['parameter_loop'] = 'parameter_loop'
  id: str
  root: str = '../../..'
  outputDirectory: str
  storePath: str | None = None
  seed: int
  execution: ExecutionConfig = ExecutionConfig()
  families: tuple[LoopFamily, ...]
  historicalArchives: tuple[str, ...] = ()
  historicalStores: tuple[str, ...] = ()
  historicalScarcityGateBridge: bool = False
  historicalRows: tuple[str, ...] = ()
  fixtures: tuple[dict, ...]
  responses: tuple[Response, ...]
  models: tuple[ModelConfig, ...]
  validation: ValidationConfig = ValidationConfig(campaignTransfer=False)
  preference: LoopPreference
  search: LoopSearch = LoopSearch()
  budgets: LoopBudgets = LoopBudgets()
  benchmark: LoopBenchmark
  cleanup: LoopCleanup = LoopCleanup()
  provenance: dict = Field(default_factory=dict)
  smoke: dict = Field(default_factory=dict)

  @model_validator(mode='after')
  def valid(self):
    for values in [[f.id for f in self.families],
                   [r.name for r in self.responses],
                   [f['workloadId'] for f in self.fixtures]]:
      if not values or len(values) != len(set(values)): raise ValueError(
        'unique nonempty declarations required')
    names = {r.name for r in self.responses}
    workload_ids = {f['workloadId'] for f in self.fixtures}
    if any(not r.workloads or not set(r.workloads) <= workload_ids for r in
           self.responses): raise ValueError(
      'response refers to missing fixtures')
    if not set(
        self.preference.primaryTargets + self.preference.topologyTargets + self.preference.guardrailTargets) <= names:
      raise ValueError('unknown preference response')
    if not self.preference.primaryTargets or not self.preference.topologyTargets or (
        self.preference.objective != 'scarce' and not self.preference.guardrailTargets):
      raise ValueError('primary, topology and guardrail outputs required')
    if self.benchmark.offSentinel and self.benchmark.offSentinel not in workload_ids:
      raise ValueError('OFF sentinel must name a declared fixture')
    if self.benchmark.offSentinel and self.benchmark.referenceMode != 'incumbent':
      raise ValueError(
        'sentinel-only OFF requires matched incumbent references')
    if self.benchmark.referenceMode == 'incumbent' and self.preference.objective != 'scarce':
      raise ValueError('incumbent references require scarce objective')
    if self.search.maxPower < self.search.power: raise ValueError(
      'maxPower below initial power')
    return self
