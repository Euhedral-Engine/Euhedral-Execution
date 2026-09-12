import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest

from pareto_weight_calibration.offline_phase import run_offline


def echo(value, progress):
  progress(f'worker={os.getpid()}')
  return dict(value=value, pid=os.getpid())


def fail(progress):
  raise ValueError('phase failed')


def wait_with_child(path, progress):
  child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])
  Path(path).write_text(f'{os.getpid()} {child.pid}')
  progress('running')
  time.sleep(120)


def cuda_allocation(progress):
  import torch
  allocation = torch.ones(8 * 1024 * 1024, device='cuda')
  torch.cuda.synchronize()
  return dict(pid=os.getpid(), allocated=torch.cuda.memory_allocated(),
              sum=float(allocation.sum()))


def gone(pid):
  stat = Path(f'/proc/{pid}/stat')
  return not stat.exists() or stat.read_text().split()[2] == 'Z'


def test_spawned_phase_result_and_progress_are_returned_after_exit():
  messages = []
  result = run_offline(__name__, 'echo', {'arbitrary': [1, 2, 3]}, progress=messages.append)
  assert result['value'] == {'arbitrary': [1, 2, 3]}
  assert result['pid'] != os.getpid() and gone(result['pid'])
  assert messages == [f"worker={result['pid']}",
                       'ECHO worker exited; offline CPU/GPU allocations released']


def test_error_propagates_and_next_phase_can_run():
  with pytest.raises(RuntimeError, match='ValueError: phase failed'):
    run_offline(__name__, 'fail')
  assert run_offline(__name__, 'echo', 7)['value'] == 7


def test_interruption_reaps_owned_phase_and_descendant(tmp_path):
  path = tmp_path / 'pids'

  def stop(message):
    raise KeyboardInterrupt()

  with pytest.raises(KeyboardInterrupt):
    run_offline(__name__, 'wait_with_child', str(path), progress=stop)
  pids = list(map(int, path.read_text().split()))
  deadline = time.monotonic() + 5
  while not all(gone(pid) for pid in pids) and time.monotonic() < deadline:
    time.sleep(.01)
  assert all(gone(pid) for pid in pids)
  assert run_offline(__name__, 'echo', 8)['value'] == 8


def test_cuda_phase_context_is_gone_before_return():
  from pareto_weight_calibration.execution_resources import cuda_driver_available
  if not cuda_driver_available(): pytest.skip('CUDA unavailable')
  # Initialization, allocations and the CUDA context belong to the child only.
  result = run_offline(__name__, 'cuda_allocation')
  assert result['allocated'] >= 32 * 1024 * 1024
  assert gone(result['pid'])
  listing = subprocess.check_output(
    ['nvidia-smi', '--query-compute-apps=pid', '--format=csv,noheader'], text=True)
  assert str(result['pid']) not in listing.splitlines()
