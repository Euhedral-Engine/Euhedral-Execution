"""Unit tests for checksum computation and sidecar verification."""

from __future__ import annotations

import hashlib
from pathlib import Path
import pytest

from pareto_weight_calibration.checksum import (
    ChecksumMismatchError,
    ChecksumVerifier,
    MissingChecksumError,
)


def test_valid_checksum_passes(tmp_path: Path):
    content = "test data\nsecond line\n"
    f = tmp_path / "data.tsv"
    f.write_text(content, encoding="utf-8")
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
    sidecar = tmp_path / "data.tsv.sha256"
    sidecar.write_text(f"{digest}\n", encoding="utf-8")

    verified = ChecksumVerifier.verify_file(f, require_sidecar=True)
    assert verified == digest.lower()


def test_sidecar_with_filename_format(tmp_path: Path):
    content = "hello world\n"
    f = tmp_path / "file.json"
    f.write_text(content, encoding="utf-8")
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
    sidecar = tmp_path / "file.json.sha256"
    sidecar.write_text(f"{digest}  file.json\n", encoding="utf-8")

    verified = ChecksumVerifier.verify_file(f, require_sidecar=True)
    assert verified == digest.lower()


def test_corrupted_file_fails(tmp_path: Path):
    content = "correct content\n"
    f = tmp_path / "data.tsv"
    f.write_text(content, encoding="utf-8")
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
    sidecar = tmp_path / "data.tsv.sha256"
    sidecar.write_text(f"{digest}\n", encoding="utf-8")

    # Corrupt the file
    f.write_text("corrupted content\n", encoding="utf-8")

    with pytest.raises(ChecksumMismatchError):
        ChecksumVerifier.verify_file(f, require_sidecar=True)


def test_missing_checksum_fails(tmp_path: Path):
    f = tmp_path / "unprotected.tsv"
    f.write_text("sample content", encoding="utf-8")

    with pytest.raises(MissingChecksumError):
        ChecksumVerifier.verify_file(f, require_sidecar=True)

    # When require_sidecar=False, it calculates and returns without error
    computed = ChecksumVerifier.verify_file(f, require_sidecar=False)
    assert computed == hashlib.sha256(b"sample content").hexdigest()
