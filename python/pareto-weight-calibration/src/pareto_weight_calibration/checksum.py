"""Streaming SHA-256 checksum computation and sidecar verification."""

from __future__ import annotations

import hashlib
from pathlib import Path


class ChecksumVerificationError(Exception):
  """Base exception for checksum verification failures."""


class ChecksumMismatchError(ChecksumVerificationError):
    """Raised when calculated artifact SHA-256 does not match sidecar digest."""


class MissingChecksumError(ChecksumVerificationError):
    """Raised when an expected .sha256 sidecar is missing."""


class ChecksumVerifier:
    """Utilities for cryptographic sidecar verification."""

    @staticmethod
    def compute_sha256(file_path: Path, chunk_size: int = 65536) -> str:
        """Calculates SHA-256 hex digest for a file using streaming chunks."""
        if not file_path.exists() or not file_path.is_file():
            raise FileNotFoundError(f"File not found or not a regular file: {file_path}")

        hasher = hashlib.sha256()
        with open(file_path, "rb") as f:
            while chunk := f.read(chunk_size):
                hasher.update(chunk)
        return hasher.hexdigest().lower()

    @classmethod
    def verify_file(cls, file_path: Path, require_sidecar: bool = True) -> str:
        """Verifies file against its <filename>.sha256 sidecar.

        Returns:
            The verified lower-case SHA-256 hex digest.

        Raises:
            MissingChecksumError: If sidecar is required but missing.
            ChecksumMismatchError: If calculated digest differs from sidecar.
            FileNotFoundError: If target file does not exist.
        """
        if not file_path.exists():
            raise FileNotFoundError(f"Target file does not exist: {file_path}")

        computed_digest = cls.compute_sha256(file_path)
        sidecar_path = file_path.parent / (file_path.name + ".sha256")

        if not sidecar_path.exists():
            if require_sidecar:
                raise MissingChecksumError(
                    f"Missing expected SHA-256 sidecar at {sidecar_path} for {file_path}"
                )
            return computed_digest

        sidecar_content = sidecar_path.read_text(encoding="utf-8").strip()
        if not sidecar_content:
            raise ChecksumMismatchError(
                f"Empty SHA-256 sidecar at {sidecar_path} for {file_path}"
            )

        # Handle sidecars with standard 'hash filename' or plain 'hash' formats
        expected_digest = sidecar_content.split()[0].lower()

        if computed_digest != expected_digest:
            raise ChecksumMismatchError(
                f"SHA-256 mismatch for {file_path}: expected {expected_digest}, computed {computed_digest}"
            )

        return computed_digest
