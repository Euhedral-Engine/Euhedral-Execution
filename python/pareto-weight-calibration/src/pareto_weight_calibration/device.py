"""Device resolution and PyTorch/CUDA tensor utilities."""

from __future__ import annotations

from typing import Optional, Union
import numpy as np
import torch

DTYPE = torch.float64


def is_cuda_available() -> bool:
  """Returns True if CUDA is available via PyTorch."""
  return torch.cuda.is_available()


def resolve_device(
    device_str: Optional[Union[str, torch.device]] = "auto") -> torch.device:
  """Resolves the computation device.

  Args:
      device_str: Device identifier ('auto', 'cuda', 'cpu', or torch.device).

  Returns:
      Resolved torch.device.

  Raises:
      RuntimeError: If 'cuda' is explicitly requested but CUDA is not available.
  """
  if isinstance(device_str, torch.device):
    if device_str.type == "cuda" and not torch.cuda.is_available():
      raise RuntimeError(
        "CUDA was requested, but CUDA is not available on this system.")
    return device_str

  if device_str is None or device_str == "auto":
    return torch.device("cuda" if torch.cuda.is_available() else "cpu")

  if device_str == "cuda" or (
      isinstance(device_str, str) and device_str.startswith("cuda:")):
    if not torch.cuda.is_available():
      raise RuntimeError(
        "CUDA was requested, but CUDA is not available on this system.")
    return torch.device(device_str)

  if device_str == "cpu":
    return torch.device("cpu")

  return torch.device(device_str)


def is_cuda(device: Optional[Union[str, torch.device]]) -> bool:
  """Returns True if the resolved device is a CUDA device."""
  try:
    dev = resolve_device(device)
    return dev.type == "cuda"
  except RuntimeError:
    return False


def to_tensor(
    array: Union[np.ndarray, torch.Tensor],
    device: Optional[Union[str, torch.device]] = None,
    dtype: torch.dtype = DTYPE,
) -> torch.Tensor:
  """Converts a NumPy array or PyTorch tensor to a tensor on the target device."""
  dev = resolve_device(device) if device is not None else None
  if isinstance(array, torch.Tensor):
    tensor = array.to(dtype=dtype)
    if dev is not None:
      tensor = tensor.to(device=dev)
    return tensor
  tensor = torch.from_numpy(np.asarray(array)).to(dtype=dtype)
  if dev is not None:
    tensor = tensor.to(device=dev)
  return tensor


def to_numpy(tensor: Union[torch.Tensor, np.ndarray]) -> np.ndarray:
  """Converts a PyTorch tensor or NumPy array to a NumPy ndarray."""
  if isinstance(tensor, np.ndarray):
    return tensor
  return tensor.detach().cpu().numpy()
