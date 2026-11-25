#!/usr/bin/env python3
"""Compile and run the full server-independent source test suite with JUnit Platform 1.11.4."""
import argparse
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--junit", type=Path, required=True, help="JUnit Platform console standalone JAR")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
java_home = os.environ.get("JAVA_HOME")
tools = {}
for name in ("java", "javac"):
    binary = name + (".exe" if os.name == "nt" else "")
    tools[name] = shutil.which(str(Path(java_home) / "bin" / binary) if java_home else binary)
    if not tools[name]:
        parser.error("Set JAVA_HOME to a JDK 21 or newer installation")
with tempfile.TemporaryDirectory(prefix="mobby-units-") as classes:
    junit = str(args.junit.resolve(strict=True))
    tests = sorted((root / "src/test/java").rglob("*.java"))
    subprocess.run([tools["javac"], "--release", "21", "-cp", junit, "-sourcepath",
                    os.pathsep.join(str(root / p) for p in ("src/main/java", "src/test/java")),
                    "-d", classes, *map(str, tests)], check=True)
    subprocess.run([tools["java"], "-jar", junit, "execute", "--class-path", classes,
                    "--scan-class-path", "--disable-ansi-colors", "--fail-if-no-tests"], check=True)
