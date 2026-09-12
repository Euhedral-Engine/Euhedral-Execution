"""Spawned offline phases: process exit releases native allocators before JMH."""
import contextlib
import importlib
import multiprocessing
import os
import signal
import traceback


def _worker(connection, module, name):
  # Spawn, never fork an initialized CUDA runtime. Own descendants such as loky.
  if os.name == 'posix': os.setsid()
  connection.send(('ready', os.getpid()))
  try:
    args, kwargs = connection.recv()
    function = getattr(importlib.import_module(module), name)
    result = function(*args, **kwargs,
                      progress=lambda message: connection.send(('progress', message)))
    connection.send(('result', result))
  except BaseException:
    connection.send(('error', traceback.format_exc()))
  finally:
    connection.close()


def _stop(process, owns_group):
  if os.name == 'posix':
    with contextlib.suppress(ProcessLookupError):
      owns_group = owns_group or os.getpgid(process.pid) == process.pid
  for sig in (signal.SIGTERM, signal.SIGKILL):
    with contextlib.suppress(ProcessLookupError):
      if owns_group and os.name == 'posix': os.killpg(process.pid, sig)
      elif process.is_alive(): os.kill(process.pid, sig)
    process.join(timeout=5)
    if not process.is_alive(): break
  # Also remove descendants if the phase exited before one of its workers.
  if owns_group and os.name == 'posix':
    with contextlib.suppress(ProcessLookupError): os.killpg(process.pid, signal.SIGKILL)
  process.join()


def run_offline(module, name, *args, progress=None, **kwargs):
  """Return compact results only after the phase process and its workers exit."""
  context = multiprocessing.get_context('spawn')
  receive, send = context.Pipe()
  process = context.Process(target=_worker,
                            args=(send, module, name))
  owns_group = False
  result = None
  received_result = False
  try:
    process.start()
    send.close()
    while True:
      if receive.poll(.2):
        try:
          kind, value = receive.recv()
        except EOFError:
          break
        if kind == 'ready':
          owns_group = True
          # Transfer potentially large inputs after start returns and ownership
          # is established, so interruption can always reap the child.
          receive.send((args, kwargs))
        elif kind == 'progress':
          if progress: progress(value)
        elif kind == 'error': raise RuntimeError(f'Offline {name} failed:\n{value}')
        elif kind == 'result':
          result, received_result = value, True
      elif not process.is_alive():
        break
    process.join()
    if process.exitcode != 0 or not received_result:
      raise RuntimeError(f'Offline {name} exited with status {process.exitcode} without a result')
  finally:
    receive.close()
    send.close()
    if process.pid is not None:
      _stop(process, owns_group)
      process.close()
  if progress: progress(f'{name.upper()} worker exited; offline CPU/GPU allocations released')
  return result
