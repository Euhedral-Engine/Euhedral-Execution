"""JSON contracts for offline arbitrary-parameter response tournaments."""

from typing import Literal
from pydantic import Field, model_validator
from .training_spec import SpecModel
from .tuning_spec import Parameter
from .execution_resources import ExecutionConfig


class HistoricalSources(SpecModel):
  adapter: Literal["scheduler_forks", "response_rows"] = "scheduler_forks"
  discovery: tuple[str, ...]
  archive: str | None = None
  rawDiscovery: tuple[str, ...] = ()
  referenceIdentity: str = ""
  centerArtifact: str = ""
  functionPath: str = "/cacheTimingFunction"
  fixedControl: dict = Field(default_factory=dict)
  fixtures: tuple[dict, ...] = ()
  expectedConfig: dict = Field(default_factory=dict)
  varyingConfigKeys: tuple[str, ...] = (
    "cacheTimingFunction",
    "cpuSet",
    "parallelSources",
    "workUnits",
  )
  schedule: dict = Field(default_factory=dict)
  requiredJvmOptions: tuple[str, ...] = ()
  runtimeSourcePatterns: tuple[str, ...] = ("*/src/main/java/*.java",)
  acceptedSourceHashes: dict[str, tuple[str, ...]] = Field(default_factory=dict)
  sourceReview: dict[str, str] = Field(default_factory=dict)
  runtimeHeaders: tuple[str, ...] = ()


class Response(SpecModel):
  name: str
  workloads: tuple[str, ...]
  role: str


class ModelConfig(SpecModel):
  id: str
  family: str
  degree: int = Field(default=1, ge=1, le=3)
  mode: Literal["native", "independent"] = "native"
  params: dict = Field(default_factory=dict)
  grid: dict[str, list] = Field(default_factory=dict)
  optional: bool = False
  inputIndices: tuple[int, ...] | None = None


class ValidationConfig(SpecModel):
  outerFolds: int = Field(default=4, ge=2)
  innerFolds: int = Field(default=3, ge=2)
  group: Literal["thetaId"] = "thetaId"
  campaignTransfer: bool = True
  selectionMetrics: tuple[Literal["regret", "rmse", "mae", "rankLoss"], ...] = (
    "regret",
    "rmse",
  )
  topK: int = Field(default=2, ge=1)
  ensembleSize: int = Field(default=3, ge=2)
  ensembleStrategies: tuple[Literal["mean", "validation_weighted"], ...] = (
    "mean",
    "validation_weighted",
  )
  weightMetric: Literal["rmse", "mae"] = "rmse"
  sampleWeight: Literal["uniform", "inverse_theta_frequency"] = "uniform"


class SearchRule(SpecModel):
  name: str
  targets: tuple[str, ...]
  reduce: Literal["mean", "min", "max"] = "min"
  cap: float | None = None


class RankingSkill(SpecModel):
  minSpearman: float = Field(default=0.2, ge=-1, le=1)
  maxRegretRatioToMean: float = Field(default=0.65, ge=0)
  minTopKRecall: float = Field(default=0.5, ge=0, le=1)
  minTheta: int = Field(default=8, ge=2)
  minCoverage: float = Field(default=0.9, gt=0, le=1)


class AbsoluteSkill(SpecModel):
  maxRmseRatioToMean: float = Field(default=0.95, gt=0)
  maxRmseRatioToLinear: float = Field(default=1.2, gt=0)
  maxRmseLog: float = Field(default=0.025, gt=0)
  maxMaeLog: float = Field(default=0.02, gt=0)
  maxAbsBiasLog: float = Field(default=0.01, ge=0)
  maxCampaignBiasLog: float = Field(default=0.025, ge=0)
  minTheta: int = Field(default=12, ge=2)
  minCampaigns: int = Field(default=2, ge=1)
  minCoverage: float = Field(default=1., gt=0, le=1)


class ReliabilityPolicy(SpecModel):
  enabled: bool = False
  ranking: RankingSkill = RankingSkill()
  absolute: AbsoluteSkill = AbsoluteSkill()
  hardSystems: tuple[str, ...] = ('off',)
  directionAlternatives: dict[str, tuple[str, ...]] = Field(
    default_factory=dict)
  softPenaltyScaleLog: float = Field(default=0.03, gt=0)
  softPenaltyCap: float = Field(default=3., gt=0)
  # Positive-direction clipping limits the influence of one enormous predicted return.
  directionCapPercent: float = Field(default=25., gt=0)
  disagreementUsefulOnly: bool = True


class RetrospectivePolicy(SpecModel):
  enabled: bool = False
  seed: int = 20260912
  randomRepeats: int = Field(default=1000, ge=1)
  batchPerFold: int = Field(default=2, ge=1)
  primaryTargets: tuple[str, ...] = ()
  topologyTargets: tuple[str, ...] = ()
  measuredGuardrailFloors: dict[str, float] = Field(default_factory=dict)
  usefulFraction: float = Field(default=0.25, gt=0, le=1)
  positiveCapPercent: float = Field(default=25., gt=0)
  knownPolicyIds: tuple[str, ...] = ()
  neighborhoodRadius: float = Field(default=0.3, gt=0)


class HistoryRisk(SpecModel):
  radius: float = Field(default=0., ge=0)
  minBlocks: int = Field(default=2, ge=2)
  measuredFloors: dict[str, float] = Field(default_factory=dict)


class UsefulHistory(SpecModel):
  evidenceScope: Literal['campaign', 'pooled'] = 'campaign'
  primaryTargets: tuple[str, ...]
  topologyTargets: tuple[str, ...]
  minPositiveFraction: float = Field(default=0.5, ge=0, le=1)
  minTopologyPercent: float = Field(default=0., gt=-100)
  minBroadPercent: float = Field(default=0., gt=-100)
  positiveCapPercent: float = Field(default=25., gt=0)
  minBlocksPerTarget: int = Field(default=2, ge=1)
  plentifulFloors: dict[str, float] = Field(default_factory=dict)
  selection: Literal['pareto_tradeoffs', 'all_qualified'] = 'pareto_tradeoffs'


class RejectedRegionPolicy(SpecModel):
  mode: Literal[
    'later_consistent_measurements'] = 'later_consistent_measurements'
  # Explicit chronology: unknown campaigns cannot constitute later rejection evidence.
  campaignOrder: tuple[str, ...] = ()
  minLaterCampaigns: int = Field(default=1, ge=1)
  minBlocksPerTarget: int = Field(default=2, ge=2)
  maxPositiveFraction: float = Field(default=0.5, ge=0, le=1)
  maxTopologyPercent: float = Field(default=0., gt=-100)
  maxBroadPercent: float = Field(default=0., gt=-100)


class KnownRegionCoverage(SpecModel):
  enabled: bool = False
  radius: float = Field(default=0.3, gt=0)
  maxRegions: int = Field(default=8, ge=1)
  minEligibleParetoPoints: int = Field(default=1, ge=1)
  maxProposals: int = Field(default=8, ge=1)
  usefulness: UsefulHistory | None = None
  rejectedRegionPolicy: RejectedRegionPolicy = RejectedRegionPolicy()
  selectionPriority: tuple[
    Literal['coverage', 'consensus', 'broad_direction', 'distance'], ...] = (
    'coverage', 'consensus', 'broad_direction', 'distance')
  protectedRoles: tuple[str, ...] = ('model_disagreement',)
  maxSearchNodes: int = Field(default=100000, ge=1)

  @model_validator(mode='after')
  def check_policy(self):
    if self.enabled and (
        self.usefulness is None or not self.usefulness.primaryTargets
        or not self.usefulness.topologyTargets):
      raise ValueError('coverage requires measured usefulness targets')
    if not self.selectionPriority or len(set(self.selectionPriority)) != len(
        self.selectionPriority):
      raise ValueError('unique coverage selection priorities required')
    order = self.rejectedRegionPolicy.campaignOrder
    if len(order) != len(set(order)):
      raise ValueError('duplicate campaign chronology')
    return self


class ReuseFit(SpecModel):
  directory: str
  lockSha256: str
  allowProposalChanges: bool = False


class Counterfactual(SpecModel):
  enabled: bool = False
  values: dict[str, float] = Field(default_factory=dict)
  suffix: str = 'zero-fifth'


class DenseInference(SpecModel):
  enabled: bool = False
  memoryBudgetGiB: float = Field(default=24, gt=0)
  chunkPower: int = Field(default=16, ge=8, le=24)
  checkpoints: tuple[int, ...] = (20, 22, 24)
  expansionPower: int | None = Field(default=None, ge=1, le=28)
  expansionMaxSeconds: float = Field(default=1800, gt=0)
  stabilityToleranceLog: float = Field(default=0.005, gt=0)
  basinDistanceTolerance: float = Field(default=0.05, gt=0)
  predictionDtype: Literal['float64'] = 'float64'
  maxDisagreementMembers: int = Field(default=3, ge=1)


class BenchmarkPreparation(SpecModel):
  workflow: Literal['cache_timing_confirmation'] = 'cache_timing_confirmation'
  stage: Literal['r23', 'topologies', 'dynamic'] = 'topologies'
  templateHarness: str
  blocks: int = Field(default=2, ge=1)
  runDirectory: str
  subdirectory: str = 'benchmark'
  functionConfigPath: str = '/cacheTimingFunction'
  fixtureBindings: dict[str, str]
  controls: tuple[dict, ...]


class ProposalSearch(SpecModel):
  enabled: bool = True
  power: int = Field(default=24, ge=1, le=28)
  seed: int
  count: int = Field(default=6, ge=1)
  minMeasuredDistance: float = Field(default=0.1, gt=0)
  minProposalDistance: float = Field(default=0.15, gt=0)
  floorPercent: dict[str, float] = Field(default_factory=dict)
  objectives: tuple[SearchRule, ...]
  roles: tuple[SearchRule, ...]
  clusters: int = Field(default=3, ge=1)
  ensembleTolerance: float = Field(default=1.5, ge=1)
  maxDisagreementLog: float | None = None
  disagreementPenalty: float = Field(default=0, ge=0)
  explorationCount: int = Field(default=1, ge=0)
  support: dict = Field(default_factory=dict)
  round: int = 1
  parentDataset: str | None = None
  activeBasins: tuple[dict, ...] = ()
  convergence: dict = Field(default_factory=dict)
  reliability: ReliabilityPolicy = ReliabilityPolicy()
  retrospective: RetrospectivePolicy = RetrospectivePolicy()
  historyRisk: HistoryRisk = HistoryRisk()
  benchmark: BenchmarkPreparation | None = None
  knownRegionCoverage: KnownRegionCoverage = KnownRegionCoverage()
  denseInference: DenseInference = DenseInference()
  counterfactual: Counterfactual = Counterfactual()


class SurrogateTask(SpecModel):
  schemaVersion: Literal[1] = 1
  kind: Literal["parameter_tournament"] = "parameter_tournament"
  id: str
  root: str = "."
  outputDirectory: str
  seed: int
  jobs: int | None = Field(default=None, ge=1)
  execution: ExecutionConfig = ExecutionConfig()
  parameters: tuple[Parameter, ...]
  dataset: HistoricalSources
  responses: tuple[Response, ...]
  systems: tuple[Literal["center", "off"], ...] = ("center", "off")
  models: tuple[ModelConfig, ...]
  validation: ValidationConfig = ValidationConfig()
  proposal: ProposalSearch
  provenance: dict = Field(default_factory=dict)
  reuseFit: ReuseFit | None = None
  requireVaryingParameters: tuple[str, ...] = ()

  @model_validator(mode="after")
  def check_names(self):
    if self.proposal.enabled and self.proposal.power > 20 and not self.proposal.denseInference.enabled:
      raise ValueError('large searches require denseInference.enabled')
    for seq in [
      [p.name for p in self.parameters],
      [r.name for r in self.responses],
      [m.id for m in self.models],
    ]:
      if not seq or len(seq) != len(set(seq)):
        raise ValueError("empty or duplicate declarations")
    if not set(self.requireVaryingParameters) <= {p.name for p in
                                                  self.parameters}:
      raise ValueError('unknown required varying parameter')
    cf = self.proposal.counterfactual
    if cf.enabled and (not cf.values or not set(cf.values) <= {p.name for p in
                                                               self.parameters}):
      raise ValueError('counterfactual requires declared parameter names')
    for p in self.parameters:
      if p.name in cf.values and not p.bounds[0] <= cf.values[p.name] <= \
                                     p.bounds[1]:
        raise ValueError('counterfactual outside parameter bounds')
    for model in self.models:
      if model.inputIndices is not None and (not model.inputIndices or
                                             len(set(
                                                 model.inputIndices)) != len(
              model.inputIndices) or
                                             any(i < 0 or i >= len(
                                                 self.parameters) for i in
                                                 model.inputIndices)):
        raise ValueError('invalid model input indices')
    targets = {s + ":" + r.name for s in self.systems for r in self.responses}
    for rule in (*self.proposal.objectives, *self.proposal.roles):
      if not rule.targets or not set(rule.targets) <= targets:
        raise ValueError("unknown proposal target")
    if not set(self.proposal.floorPercent) <= targets:
      raise ValueError("unknown floor target")
    if any(x <= -100 for x in self.proposal.floorPercent.values()):
      raise ValueError("invalid return floor")
    if any(not values for m in self.models for values in m.grid.values()):
      raise ValueError("empty model grid")
    extra = [*self.proposal.retrospective.primaryTargets,
             *self.proposal.retrospective.topologyTargets,
             *self.proposal.retrospective.measuredGuardrailFloors,
             *self.proposal.historyRisk.measuredFloors]
    coverage = self.proposal.knownRegionCoverage
    if coverage.enabled:
      if coverage.maxProposals < self.proposal.count:
        raise ValueError(
          'coverage maximum must preserve normal proposal budget')
      u = coverage.usefulness
      extra.extend([*u.primaryTargets, *u.topologyTargets, *u.plentifulFloors])
      if any(v <= -100 for v in u.plentifulFloors.values()):
        raise ValueError('invalid usefulness floor')
      if not self.proposal.reliability.enabled:
        raise ValueError(
          'known-region coverage requires reliability-aware proposal search')
    for name, alternatives in self.proposal.reliability.directionAlternatives.items():
      if not alternatives: raise ValueError(
        'direction alternatives must be nonempty')
      extra.extend([name, *alternatives])
    if not set(extra) <= targets: raise ValueError(
      'unknown reliability/retrospective target')
    if self.proposal.retrospective.enabled and not self.proposal.retrospective.primaryTargets:
      raise ValueError('retrospective primary targets required')
    for floors in [self.proposal.retrospective.measuredGuardrailFloors,
                   self.proposal.historyRisk.measuredFloors]:
      if any(v <= -100 for v in floors.values()): raise ValueError(
        'invalid measured floor')
    if self.proposal.reliability.enabled and not {'mean', 'linear'} <= {m.family
                                                                        for m in
                                                                        self.models}:
      raise ValueError('reliability requires mean and linear diagnostics')
    return self
