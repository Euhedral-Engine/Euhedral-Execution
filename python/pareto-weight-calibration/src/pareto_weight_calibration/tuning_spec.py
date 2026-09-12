"""Dimension-independent contracts for bounded offline parameter tuning."""
from typing import Literal
from pydantic import Field, model_validator
from .training_spec import SpecModel, Variable


class Parameter(SpecModel):
  name: str = Field(pattern=r'^[A-Za-z_][A-Za-z0-9_]*$')
  path: str
  center: float
  offset: float = 0.0
  bounds: tuple[float, float]
  sign: Literal['negative', 'positive', 'any'] = 'any'
  transform: Literal['identity', 'log'] = 'identity'

  @model_validator(mode='after')
  def valid(self):
    lo, hi = self.bounds
    if not lo < hi or not lo <= self.center <= hi:
      raise ValueError('bounds must contain center with nonzero width')
    if not self.path.startswith('/') or self.path.endswith('/'):
      raise ValueError('parameter requires exact JSON Pointer')
    if self.sign == 'negative' and hi >= 0 or self.sign == 'positive' and lo <= 0:
      raise ValueError('bounds violate strict parameter sign')
    if self.transform == 'log' and lo <= 0:
      raise ValueError('log parameters must be positive')
    return self


class SobolDesign(SpecModel):
  seed: int
  scramble: bool = True
  count: int = Field(ge=1)
  poolPower: int = Field(default=12, ge=1, le=20)
  startIndex: int = Field(default=0, ge=0)


class Response(SpecModel):
  name: str = Field(pattern=r'^[A-Za-z_][A-Za-z0-9_]*$')
  workloads: tuple[str, ...]
  role: Literal['scarce', 'plentiful', 'scarce_body', 'plentiful_body']

  @model_validator(mode='after')
  def valid(self):
    if not self.workloads or len(set(self.workloads)) != len(self.workloads):
      raise ValueError('response workloads must be nonempty and unique')
    return self


class PredictionRule(SpecModel):
  name: str
  targets: tuple[str, ...]
  reduction: Literal['min', 'mean'] = 'min'
  capAtZero: bool = False


class ProposalConfig(SpecModel):
  design: SobolDesign
  count: int = Field(default=6, ge=1)
  minMeasuredDistance: float = Field(default=.10, gt=0)
  minProposalDistance: float = Field(default=.15, gt=0)
  objectives: tuple[PredictionRule, ...]
  roles: tuple[PredictionRule, ...]
  floors: dict[str, float] = Field(default_factory=dict)
  centerRetention: dict[str, float] = Field(default_factory=dict)
  improvementTargets: tuple[str, ...]


class TuningSpec(SpecModel):
  schemaVersion: Literal[1] = 1
  id: str = Field(pattern=r'^[a-zA-Z0-9_-]+$')
  sourcePolicyId: str
  centerId: str
  parameters: tuple[Parameter, ...]
  initial: SobolDesign
  responses: tuple[Response, ...]
  proposal: ProposalConfig
  runtime: dict
  fixtures: tuple[dict, ...]
  blocks: int = Field(default=2, ge=2)
  provenance: dict

  @model_validator(mode='after')
  def valid(self):
    if not self.parameters or not self.responses:
      raise ValueError('parameters and responses required')
    for values in ([p.name for p in self.parameters],
                   [p.path for p in self.parameters],
                   [r.name for r in self.responses]):
      if len(set(values)) != len(values):
        raise ValueError('duplicate names/paths')
    paths = [p.path for p in self.parameters]
    if any(a != b and b.startswith(a + '/') for a in paths for b in paths):
      raise ValueError('overlapping parameter paths')
    names = {r.name for r in self.responses}
    selected = set(self.proposal.floors) | set(
      self.proposal.centerRetention) | set(self.proposal.improvementTargets)
    for rule in self.proposal.objectives + self.proposal.roles:
      selected.update(rule.targets)
      if not rule.targets:
        raise ValueError('empty prediction rule')
    if not selected <= names or not self.proposal.objectives or not self.proposal.improvementTargets:
      raise ValueError('unknown/empty proposal response selection')
    return self
