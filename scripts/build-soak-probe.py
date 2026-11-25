"""Build only the disposable telemetry probe using the restored Gradle dependency cache."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--jdk', type=Path, required=True)
p.add_argument('--gradle-home', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
args = p.parse_args()
root = Path(__file__).resolve().parents[1] / 'src/soakProbe'
classpath = sorted((args.gradle_home/'caches/modules-2/files-2.1').rglob('*.jar'))
with tempfile.TemporaryDirectory() as classes:
    subprocess.run([args.jdk/'bin/javac', '--release', '21', '-cp', os.pathsep.join(map(str,classpath)),
                    '-d', classes, *sorted((root/'java').rglob('*.java'))], check=True)
    subprocess.run([args.jdk/'bin/jar','cf',args.output,'-C',classes,'.','-C',root/'resources','.'],check=True)
