"""One concurrency budget for offline fits and predictions; no pools survive measurement."""
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor, wait
from functools import lru_cache
from pathlib import Path
from typing import Literal
import os
import subprocess
import threading
import time
import numpy as np
from pydantic import Field
from .training_spec import SpecModel


class ExecutionConfig(SpecModel):
  device: Literal['auto', 'cpu', 'cuda'] = 'auto'
  fitWorkers: int | Literal['auto'] = 'auto'
  predictWorkers: int | Literal['auto'] = 'auto'
  backend: Literal['auto', 'threads', 'processes'] = 'auto'
  nativeThreadsPerWorker: int = Field(default=1, ge=1)
  memoryBudgetGiB: float = Field(default=48, gt=0)
  gpuConcurrency: int = Field(default=1, ge=1)
  gpuMemoryBudgetGiB: float = Field(default=10, gt=0)
  cudaMinFitRows: int = Field(default=2048, ge=1)
  cudaMinPredictRows: int = Field(default=4096, ge=1)

  def resolved(self):
    cpus = len(os.sched_getaffinity(0)) if hasattr(os,
                                                   'sched_getaffinity') else os.cpu_count() or 1
    quota = Path('/sys/fs/cgroup/cpu.max')
    if quota.exists():
      q, p = quota.read_text().split()
      if q != 'max': cpus = min(cpus, max(1, int(q) // int(p)))
    memory = os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES')
    limit = Path('/sys/fs/cgroup/memory.max')
    if limit.exists() and limit.read_text().strip() != 'max': memory = min(
      memory, int(limit.read_text()))
    budget = min(self.memoryBudgetGiB * 2 ** 30, memory * .8)

    def workers(value):
      if value != 'auto' and value < 1: raise ValueError(
        'worker counts must be positive')
      return max(1, min(cpus // self.nativeThreadsPerWorker or 1,
                        int(budget / 2 ** 30) or 1,
                        cpus if value == 'auto' else value))

    gpu = self.device != 'cpu' and cuda_driver_available()
    return dict(allowedCpus=cpus, fitWorkers=workers(self.fitWorkers),
                predictWorkers=workers(self.predictWorkers),
                nativeThreadsPerWorker=min(cpus, self.nativeThreadsPerWorker),
                memoryBudgetBytes=int(budget),
                requestedDevice=self.device, nativeCuda=gpu,
                backend='processes' if self.backend == 'auto' else self.backend,
                gpuConcurrency=self.gpuConcurrency,
                gpuMemoryBudgetGiB=self.gpuMemoryBudgetGiB,
                fallback=None if gpu or self.device == 'cpu' else 'CUDA driver unavailable; parallel CPU')


@lru_cache(None)
def cuda_driver_available():
  try:
    p = subprocess.run(
        ['nvidia-smi', '--query-gpu=name', '--format=csv,noheader'],
        capture_output=True, text=True, timeout=5)
    return p.returncode == 0 and bool(p.stdout.strip())
  except (OSError, subprocess.TimeoutExpired):
    return False


def execution_config(spec, overrides=None):
  config = spec.execution.model_dump()
  if getattr(spec, 'jobs', None) is not None and config['fitWorkers'] == 'auto':
    config['fitWorkers'] = spec.jobs
  config.update({k: v for k, v in (overrides or {}).items() if v is not None})
  return ExecutionConfig.model_validate(config)


def _invoke(function, item, native):
  from threadpoolctl import threadpool_limits
  with threadpool_limits(limits=native): return function(item)


class ExecutionPool:
  """The caller owns this pool. Families/folds share it and are never outer-parallel too."""

  def __init__(self, config):
    self.config = config;
    self.resources = config.resolved();
    self.fit_pool = None;
    self.predict_pool = None
    self.fit_threads = None;
    self.gpu_pool = None;
    self.gpu_slots = threading.BoundedSemaphore(config.gpuConcurrency)
    self.events = [];
    self.closed = False;
    self.cleanups = []

  def map_fit(self, function, items, gpu_predicate=None):
    from joblib import Parallel, delayed, parallel_config
    if self.closed: raise RuntimeError('pool is closed')
    items = list(items)
    if not items: return []
    gpu = [i for i, x in enumerate(items) if
           gpu_predicate and self.resources['nativeCuda'] and gpu_predicate(x)]
    cpu = [i for i in range(len(items)) if i not in gpu];
    result = {};
    gpu_count = len(gpu)
    # GPU jobs stay in this process; loky CPU workers use spawn, never fork a CUDA runtime.
    futures = {}
    if gpu and self.resources['fitWorkers'] == 1:
      for i in gpu: result[i] = function(items[i])
      gpu = []
    if gpu:
      if self.gpu_pool is None: self.gpu_pool = ThreadPoolExecutor(
        max_workers=min(self.config.gpuConcurrency,
                        self.resources['fitWorkers']))
      futures = {i: self.gpu_pool.submit(function, items[i]) for i in gpu}
    workers = max(1, self.resources['fitWorkers'] - (
      min(self.config.gpuConcurrency, self.resources['fitWorkers'] - 1) if
      self.resources['nativeCuda'] else 0))
    if cpu:
      if workers == 1:
        values = [
          _invoke(function, items[i], self.resources['nativeThreadsPerWorker'])
          for i in cpu]
      elif self.resources['backend'] == 'threads':
        # A process-wide native limit is installed around genuinely parallel jobs.
        from threadpoolctl import threadpool_limits
        with threadpool_limits(limits=self.resources['nativeThreadsPerWorker']):
          if self.fit_threads is None: self.fit_threads = ThreadPoolExecutor(
            max_workers=workers)
          values = list(self.fit_threads.map(function, [items[i] for i in cpu]))
      else:
        # Joblib reuses its loky executor and automatically memmaps large read-only arrays.
        with parallel_config(backend='loky',
                             inner_max_num_threads=self.resources[
                               'nativeThreadsPerWorker']):
          if self.fit_pool is None: self.fit_pool = Parallel(n_jobs=workers,
                                                             max_nbytes='1M',
                                                             mmap_mode='r')
          values = self.fit_pool(delayed(_invoke)(function, items[i],
                                                  self.resources[
                                                    'nativeThreadsPerWorker'])
                                 for i in cpu)
      result.update(zip(cpu, values))
    result.update((i, f.result()) for i, f in futures.items())
    self.events.append(dict(phase='fit', tasks=len(items), cpuWorkers=workers,
                            gpuTasks=gpu_count))
    return [result[i] for i in range(len(items))]

  def map_predict(self, function, items, workers=None):
    from threadpoolctl import threadpool_limits
    if self.closed: raise RuntimeError('pool is closed')
    items = list(items);
    count = min(workers or self.resources['predictWorkers'], len(items))
    if count < 1: return []
    if self.predict_pool is None: self.predict_pool = ThreadPoolExecutor(
      max_workers=self.resources['predictWorkers'])
    native = self.resources['nativeThreadsPerWorker']
    if len(items) == 1 and self.resources['predictWorkers'] > 1:
      native = max(native, self.resources['allowedCpus'])
    with threadpool_limits(limits=native):
      if count == 1:
        result = [function(x) for x in items]
      else:
        # Start at most count requests; a slow fit does not stall the next wave.
        slots = threading.BoundedSemaphore(count)
        def bounded(item):
          with slots: return function(item)
        futures = [self.predict_pool.submit(bounded, item) for item in items]
        try:
          result = [future.result() for future in futures]
        finally:
          # Batch cleanup must not race a sibling still allocating GPU tensors.
          wait(futures)
    self.events.append(dict(phase='predict', tasks=len(items), workers=count))
    return result

  def close(self):
    if self.closed: return
    for p in [self.predict_pool, self.fit_threads, self.gpu_pool]:
      if p: p.shutdown(wait=True, cancel_futures=True)
    if self.fit_pool is not None:
      from joblib.externals.loky import get_reusable_executor
      get_reusable_executor().shutdown(wait=True, kill_workers=True)
    for callback in self.cleanups: callback()
    self.cleanups = [];
    # Release cyclic temporaries and allocator reservations only at the offline
    # phase boundary, after workers and adapter-owned tensors are gone.
    import gc
    import sys
    gc.collect()
    torch = sys.modules.get('torch')
    if self.resources[
      'nativeCuda'] and torch is not None and torch.cuda.is_initialized():
      torch.cuda.synchronize()
      torch.cuda.empty_cache()
    self.closed = True

  def __enter__(self):
    return self

  def __exit__(self, *args):
    self.close()
