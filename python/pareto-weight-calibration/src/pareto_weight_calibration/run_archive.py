"""One TSV containing queryable JVM rows and lossless, checksummed original artifacts."""

import base64
import csv
import hashlib
import json
import re
import statistics
import zlib
from pathlib import Path

FIELDS = (
  "recordType",
  "campaignId",
  "runId",
  "policyId",
  "workloadId",
  "blockId",
  "forkId",
  "sources",
  "workUnits",
  "timingFunctionJson",
  "calibrationConfigJson",
  "jmhConfigJson",
  "executionsPerSecond",
  "measurementWindowsJson",
  "configPath",
  "logPath",
  "path",
  "bytes",
  "sha256",
  "encoding",
  "payload",
)


def sha_bytes(data):
  return hashlib.sha256(data).hexdigest()


def rows(path):
  csv.field_size_limit(2 ** 31 - 1)
  with path.open() as f:
    yield from csv.DictReader(f, delimiter="\t")


def pack(root, directories, output, original_root=None):
  if output.exists():
    raise ValueError("archive output already exists")
  files = sorted(p for d in directories for p in d.rglob("*") if p.is_file())
  if any(p.is_symlink() for p in files):
    raise ValueError("do not archive symlinks")
  tmp = output.with_suffix(output.suffix + ".tmp")
  output.parent.mkdir(parents=True, exist_ok=True)
  with tmp.open("x") as f:
    writer = csv.DictWriter(f, fieldnames=FIELDS, delimiter="\t")
    writer.writeheader()
    writer.writerow(
        dict(
            recordType="metadata",
            encoding="json",
            payload=json.dumps(
                dict(
                    schemaVersion=1,
                    originalRoot=str((original_root or root).resolve()),
                    directories=[str(d.relative_to(root)) for d in directories],
                    files=len(files),
                )
            ),
        )
    )
    for path in files:
      data = path.read_bytes()
      writer.writerow(
          dict(
              recordType="artifact",
              campaignId=path.relative_to(root).parts[1],
              path=str(path.relative_to(root)),
              bytes=len(data),
              sha256=sha_bytes(data),
              encoding="zlib+base64",
              payload=base64.b64encode(zlib.compress(data, 9)).decode(),
          )
      )
      if path.name == "trial_config.json":
        trial = json.loads(data)
        cfg = trial.get("calibrationConfig", {})
        log = path.parent / "benchmark_output.log"
        text = log.read_text() if log.exists() else ""
        windows = [
          float(v)
          for v in re.findall(
              r"^\s+executions:\s+([\d.eE+-]+)\s+ops/s\s*$", text, re.M
          )
        ]
        labels = trial.get("labels") or {}
        origin = trial.get("origin") or {}
        writer.writerow(
            dict(
                recordType="run",
                campaignId=path.parent.parent.name,
                runId=path.parent.name,
                policyId=labels.get(
                    "policyId",
                    (
                      "fixed"
                      if cfg.get("cacheTimingFunction") is None
                      else "unlabelled_live"
                    ),
                ),
                workloadId=labels.get("workloadId", ""),
                blockId=origin.get("sampleIndex", ""),
                forkId="1" if trial.get("forks") == 1 else "",
                sources=cfg.get("parallelSources"),
                workUnits=cfg.get("workUnits"),
                timingFunctionJson=json.dumps(cfg.get("cacheTimingFunction")),
                calibrationConfigJson=json.dumps(cfg),
                jmhConfigJson=json.dumps(
                    {k: v for k, v in trial.items() if k != "calibrationConfig"}
                ),
                executionsPerSecond=statistics.mean(windows) if windows else "",
                measurementWindowsJson=json.dumps(windows),
                configPath=str(path.relative_to(root)),
                logPath=str(log.relative_to(root)) if log.exists() else "",
            )
        )
  verify(tmp, root)
  tmp.rename(output)
  return verify(output, root)


def verify(archive, root=None):
  seen = set()
  runs = 0
  windows = 0
  size = 0
  metadata = None
  for row in rows(archive):
    if row["recordType"] == "metadata":
      metadata = json.loads(row["payload"])
      continue
    if row["recordType"] == "run":
      runs += 1
      windows += len(json.loads(row["measurementWindowsJson"]))
      continue
    if row["recordType"] != "artifact":
      raise ValueError("unknown TSV record")
    path = Path(row["path"])
    if path.is_absolute() or ".." in path.parts or row["path"] in seen:
      raise ValueError("unsafe or duplicate path")
    seen.add(row["path"])
    data = zlib.decompress(base64.b64decode(row["payload"], validate=True))
    if len(data) != int(row["bytes"]) or sha_bytes(data) != row["sha256"]:
      raise ValueError("artifact checksum mismatch")
    if root is not None and data != (root / path).read_bytes():
      raise ValueError("archive/original mismatch")
    size += len(data)
  if metadata is None or metadata["files"] != len(seen):
    raise ValueError("incomplete archive inventory")
  if root is not None:
    expected = {
      str(p.relative_to(root))
      for d in metadata["directories"]
      for p in (root / d).rglob("*")
      if p.is_file()
    }
    if expected != seen:
      raise ValueError("source inventory changed during archive")
  return dict(
      artifactFiles=len(seen),
      runRows=runs,
      measurementWindows=windows,
      originalBytes=size,
      archiveBytes=archive.stat().st_size,
      archiveSha256=sha_bytes(archive.read_bytes()),
      directories=metadata["directories"],
  )


def restore(archive, destination):
  verify(archive)
  metadata = None
  for row in rows(archive):
    if row["recordType"] == "metadata":
      metadata = json.loads(row["payload"])
    if row["recordType"] != "artifact":
      continue
    target = destination / row["path"]
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("xb") as f:
      f.write(zlib.decompress(base64.b64decode(row["payload"])))
  return metadata


def read_file(archive, path):
  """Read one original file without restoring the archived directory tree."""
  for row in rows(archive):
    if row['recordType'] == 'artifact' and row['path'] == str(path):
      data = zlib.decompress(base64.b64decode(row['payload'], validate=True))
      if len(data) != int(row['bytes']) or sha_bytes(data) != row['sha256']:
        raise ValueError('artifact checksum mismatch')
      return data
  raise FileNotFoundError(str(path))


def main():
  import argparse

  parser = argparse.ArgumentParser(description=__doc__)
  sub = parser.add_subparsers(dest="command", required=True)
  for name in ["verify", "restore"]:
    command = sub.add_parser(name)
    command.add_argument("--archive", type=Path, required=True)
    if name == "restore":
      command.add_argument("--destination", type=Path, required=True)
  args = parser.parse_args()
  result = (
    verify(args.archive)
    if args.command == "verify"
    else restore(args.archive, args.destination)
  )
  print(json.dumps(result, indent=2))


if __name__ == "__main__":
  main()
