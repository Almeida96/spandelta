"""Check the release-gate contract through the installed application."""
import json
import os
import subprocess
import sys
from pathlib import Path

dist, demo = map(Path, sys.argv[1:3])
java = Path(os.environ["JAVA_HOME"], "bin", "java.exe" if os.name == "nt" else "java")
command = [str(java), "-cp", str(dist / "lib" / "*"), "io.github.almeida96.spandelta.MainKt"]
db = str(demo / "traces.db")


def run(expected_code, *args):
    result = subprocess.run([*command, *args], capture_output=True, text=True, encoding="utf-8", timeout=30)
    assert result.returncode == expected_code, (args, result.returncode, result.stderr)
    return json.loads(result.stdout)


base = ["compare", "--db", db, "--baseline", "baseline", "--candidate", "candidate", "--gate"]
assert run(2, *base)["verdict"] == "REGRESSION"
assert run(3, *base, "--min-samples", "41")["verdict"] == "INCONCLUSIVE"
assert run(0, *base, "--min-delta-ms", "1000", "--error-delta-pp", "100")["verdict"] == "PASS"
receipt = run(0, "import", "--db", db, "--dataset", "baseline", "--file", str(demo / "baseline.json"))
assert receipt == {"inserted": 0, "duplicates": 120}
print("Gate codes, sample threshold and idempotent replay verified.")
