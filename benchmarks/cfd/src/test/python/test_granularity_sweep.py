"""Offline sweep preparation and partial/unstable result handling."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[3]
SCRIPT = MODULE / "src/main/scripts/euhedral-cfd-sweep"
SUITE = MODULE / "suites/normal.json"


class GranularitySweepTest(unittest.TestCase):
    def test_prepare_all_sizes_without_launching_or_changing_the_stock_suite(self):
        before = SUITE.read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(SCRIPT), "--suite", str(SUITE),
                                     "--output", directory, "--prepare-only", "--launcher", "/nonexistent"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            suites = list(Path(directory).glob("sweep-*/*/suite.json"))
            self.assertEqual(6, len(suites))
            self.assertEqual({1, 2, 4, 8, 16, 32}, {json.loads(p.read_text())["brick"]["nx"] for p in suites})
            for path in suites:
                suite = json.loads(path.read_text())
                config = json.loads(Path(suite["cases"][0]["config"]).read_text())
                self.assertEqual({"nx": 256, "ny": 256, "nz": 256}, config["grid"])
                self.assertEqual(1000, config["execution"]["steps"])
                self.assertGreater(config["memoryLimitBytes"], 20 * 1024**3)
                self.assertEqual(["euhedral-workers", "fjp", "static"], [v["id"] for v in suite["variants"]])
                for variant in suite["variants"]:
                    self.assertEqual(suite["brick"], variant["brick"], "sweeps override backend-specific defaults")
            self.assertEqual(before, SUITE.read_bytes())

    def test_unstable_forks_retain_timing_even_when_qualified_mlups_is_absent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            launcher = root / "fake-launcher"
            launcher.write_text("#!" + sys.executable + "\n" + '''
import json, sys
from pathlib import Path
suite = json.loads(Path(sys.argv[-1]).read_text())
run = Path(suite["outputDirectory"]) / "benchmark-test"
case = run / "case"
case.mkdir(parents=True)
(case / "configuration.json").write_text(Path(suite["cases"][0]["config"]).read_text())
forks = []
for n in range(2):
    fork = case / str(n)
    fork.mkdir()
    (fork / "jmh.json").write_text('[{"secondaryMetrics": {}}]')
    (fork / "trial.json").write_text('{"framesCreated": 7}')
    forks.append(dict(directory=str(fork), secondsPerInvocation=0.01, processElapsedNs=1000000000))
report = dict(validationReport=str(run / "validation.json"), cases=[dict(directory=str(case), variants=[dict(
    backend="euhedral", status="UNSTABLE", resolvedSources=1, workers=1, meanSeconds=0.01,
    forkStdDevSeconds=0.001, forkCv=0.2, forks=forks)])])
(run / "report.json").write_text(json.dumps(report))
raise SystemExit(4)
''')
            launcher.chmod(0o755)
            result = subprocess.run([sys.executable, str(SCRIPT), "--suite", str(SUITE), "--short",
                                     "--sizes", "1", "--workers", "1", "--output", str(root / "output"),
                                     "--launcher", str(launcher)], capture_output=True, text=True, timeout=10)
            self.assertEqual(4, result.returncode, result.stderr)
            report = json.loads(next(root.glob("output/sweep-*/sweep.json")).read_text())
            row = report["rows"][0]
            self.assertEqual("UNSTABLE", row["status"])
            self.assertIsNone(row["mlups"])
            self.assertEqual(0.002, row["timestepSeconds"])
            self.assertEqual(32768, row["logicalRanges"])
            self.assertEqual([7, 7], [fork["framesCreated"] for fork in row["forks"]])


if __name__ == "__main__":
    unittest.main()
