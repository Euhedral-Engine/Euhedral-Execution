#!/usr/bin/env python3
"""Build a separate pinned OpenLB installation; never modify an existing destination."""
import argparse
import hashlib
import json
import pathlib
import shutil
import shlex
import subprocess
import tarfile

ARCHIVE_SHA256 = "e4d2421b50643482036917c4416145f7d6b495492f3afac64e048212361c8b3e"
FLAGS = "-O2 -std=c++20 -pthread -DPLATFORM_CPU_SISD -DOLB_VERSION=\"1.9.0\""

def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=pathlib.Path, required=True)
    parser.add_argument("--destination", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if sha(args.archive) != ARCHIVE_SHA256:
        parser.error("archive is not the published OpenLB 1.9.0 release")
    dest = args.destination.resolve()
    dest.mkdir(parents=True, exist_ok=False)
    with tarfile.open(args.archive) as archive:
        archive.extractall(dest, filter="data")
    root = dest / "olb-1.9r0"
    source = pathlib.Path(__file__).with_name("reference.cpp")
    shutil.copy2(source, dest / "reference.cpp")
    original_config = root / "config.mk"
    shutil.copy2(original_config, root / "config.release.mk")
    original_config.write_text("CXX := g++\nCC := gcc\nCXXFLAGS := -O2 -std=c++20\n"
        "PARALLEL_MODE := OFF\nPLATFORMS := CPU_SISD\nFLOATING_POINT_TYPE := double\n"
        "USE_EMBEDDED_DEPENDENCIES := ON\n")
    compiler = subprocess.check_output(["g++", "--version"], text=True)
    with (dest / "build.log").open("w") as log:
        subprocess.run(["make", "-j2"], cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=900)
        command = ["g++", *FLAGS.split(), "-I"+str(root/"src"), "-I"+str(root/"external/tinyxml2"),
            "-I"+str(root/"external/zlib"), str(dest/"reference.cpp"), "-L"+str(root/"build/lib"),
            "-L"+str(root/"external/lib"), "-lolbcore", "-ltinyxml2", "-lz", "-o", str(dest/"cfd-openlb")]
        log.write(shlex.join(command) + "\n")
        log.flush()
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=900)
    identity = dict(protocol=1, version="1.9.0", archiveSha256=ARCHIVE_SHA256,
        source="https://zenodo.org/records/17899765/files/olb-1.9r0.tgz", compiler=compiler,
        flags=FLAGS, precision="double", platform="CPU_SISD", parallelMode="OFF",
        configSha256=sha(original_config), coreConfiguration=original_config.read_text(), compileCommand=command,
        buildLogSha256=sha(dest/"build.log"), adapterSha256=sha(source), executableSha256=sha(dest/"cfd-openlb"))
    (dest/"identity.json").write_text(json.dumps(identity, indent=2)+"\n")
    print(f"OPENLB_HOME={dest}")

if __name__ == "__main__":
    main()
