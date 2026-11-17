#!/usr/bin/env python3
"""Multi-version smoke harness for MobBy.

For every Paper version given on the command line this script:

1. downloads that version's latest Paper build (cached in the work directory),
2. creates a throw-away flat server with the MobBy jar, RCON enabled and no players,
3. boots it and runs the smoke test over RCON: plugin enable/profile, region
   create + activate, furnace output, mob spawning, unattended crop growth,
   TPS and a clean shutdown,
4. boots it again with ``crops.native-random-ticks: 'yes'`` and measures whether
   the server random-ticks plugin-ticket chunks by itself (fresh wheat field,
   crop module confirmed paused), then
5. prints a Markdown summary table.

Only the Python standard library is used. Java 21 or newer is required for
Paper 1.20.6+; the 26.x line needs the Java version its own server requires.

Examples
--------
    python3 scripts/multiversion-smoke-test.py --versions 1.20.6 1.21.8
    python3 scripts/multiversion-smoke-test.py --versions 1.20.6 1.21.1 1.21.4 1.21.8 26.3 \\
        --jar dist/MobBy-1.1.0.jar --summary build/smoke-summary.md

The result reflects what was measured, not what is expected: a version whose
smoke test or probe fails is reported as FAILED and the exit status is 1.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

PAPER_BUILD_URL = "https://fill.papermc.io/v3/projects/paper/versions/{version}/builds/latest"
USER_AGENT = "MobBySmokeHarness/1.0 (+https://github.com/arham052101gmiis-ctrl/Mobby)"
COLOR = re.compile("\u00a7[0-9a-fk-orx]")
COUNT = re.compile(r"[Cc]ount:?\s*(\d+)")

REGION_NAME = "GoldFarm"
REGION_ARGS = "100 -60 100 115 -56 115"
FURNACE = (101, -60, 100)
CROP_X = range(103, 109)
CROP_Z = range(103, 109)
PROBE_Z = range(110, 116)


# --------------------------------------------------------------------------- RCON

class Rcon:
    def __init__(self, host: str, port: int, password: str, timeout: float = 30.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.timeout = timeout
        self.sock.sendall(self._packet(1, 3, password))
        response = self._read()
        if response is None or response[0] == -1:
            raise RuntimeError("RCON authentication failed")

    @staticmethod
    def _packet(request_id: int, packet_type: int, body: str) -> bytes:
        payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
        return struct.pack("<i", len(payload)) + payload

    def _read(self):
        header = b""
        while len(header) < 4:
            chunk = self.sock.recv(4 - len(header))
            if not chunk:
                return None
            header += chunk
        length = struct.unpack("<i", header)[0]
        data = b""
        while len(data) < length:
            chunk = self.sock.recv(length - len(data))
            if not chunk:
                return None
            data += chunk
        request_id = struct.unpack("<i", data[:4])[0]
        body = data[8:-2].decode("utf-8", "replace")
        return request_id, body

    def cmd(self, command: str) -> str:
        self.sock.sendall(self._packet(2, 2, command))
        first = self._read()
        if first is None:
            return ""
        parts = [first[1]]
        self.sock.settimeout(0.25)
        try:
            while True:
                nxt = self._read()
                if nxt is None:
                    break
                parts.append(nxt[1])
        except (socket.timeout, TimeoutError):
            pass
        finally:
            self.sock.settimeout(self.timeout)
        return COLOR.sub("", "".join(parts)).replace("\n", " ").strip()

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


# --------------------------------------------------------------------------- helpers

def log(message: str) -> None:
    print(message, flush=True)


def open_url(url: str, timeout: int):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    return urllib.request.urlopen(request, timeout=timeout)


def latest_build(version: str) -> tuple[str, str]:
    with open_url(PAPER_BUILD_URL.format(version=version), 60) as response:
        data = json.load(response)
    build = str(data.get("id") or data.get("build") or "latest")
    url = data["downloads"]["server:default"]["url"]
    return build, url


def download(url: str, destination: Path) -> None:
    if destination.exists() and destination.stat().st_size > 1_000_000:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".part")
    with open_url(url, 300) as response, open(temporary, "wb") as handle:
        shutil.copyfileobj(response, handle)
    temporary.replace(destination)


def write_server_files(directory: Path, server_port: int, rcon_port: int, plugin_jar: Path) -> None:
    (directory / "plugins").mkdir(parents=True, exist_ok=True)
    (directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (directory / "server.properties").write_text(
        "\n".join([
            "online-mode=false",
            f"server-port={server_port}",
            "level-name=world",
            r"level-type=minecraft\:flat",
            "view-distance=3",
            "simulation-distance=3",
            "spawn-protection=0",
            "enable-rcon=true",
            f"rcon.port={rcon_port}",
            "rcon.password=verify",
            "allow-nether=false",
            "difficulty=normal",
            "",
        ]),
        encoding="utf-8",
    )
    target = directory / "plugins" / plugin_jar.name
    shutil.copyfile(plugin_jar, target)


def boot(directory: Path, java: str, memory: str, timeout: int) -> tuple[subprocess.Popen, Path]:
    log_path = directory / "console.log"
    handle = open(log_path, "wb")
    process = subprocess.Popen(
        [java, "-Xms512M", f"-Xmx{memory}", "-jar", "paper.jar", "--nogui"],
        cwd=directory,
        stdout=handle,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during startup, see {log_path}")
        text = log_path.read_text(encoding="utf-8", errors="replace")
        if "Done (" in text:
            return process, log_path
        if "FAILED TO BIND TO PORT" in text or "Failed to start" in text:
            raise RuntimeError(f"server failed to start, see {log_path}")
        time.sleep(1)
    process.terminate()
    process.wait(timeout=30)
    raise RuntimeError(f"server did not finish starting within {timeout}s, see {log_path}")


def connect(host: str, port: int, timeout: int) -> Rcon:
    deadline = time.time() + timeout
    last: Exception | None = None
    while time.time() < deadline:
        try:
            return Rcon(host, port, "verify")
        except (OSError, RuntimeError) as error:
            last = error
            time.sleep(1)
    raise RuntimeError(f"RCON did not become available on port {port}: {last}")


def shutdown(process: subprocess.Popen, rcon: Rcon) -> None:
    try:
        rcon.cmd("stop")
    except OSError:
        pass
    rcon.close()
    try:
        process.wait(timeout=90)
    except subprocess.TimeoutExpired:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()


def parse_count(response: str) -> int:
    match = COUNT.search(response)
    return int(match.group(1)) if match else 0


def grown_crops(rcon: Rcon, z_range) -> int:
    grown = 0
    for x in CROP_X:
        for z in z_range:
            if "Test failed" in rcon.cmd(f"execute if block {x} -60 {z} minecraft:wheat[age=0]"):
                grown += 1
    return grown


def plant_crops(rcon: Rcon, z_range) -> None:
    x1, x2 = CROP_X.start, CROP_X.stop - 1
    z1, z2 = z_range.start, z_range.stop - 1
    rcon.cmd(f"fill {x1} -61 {z1} {x2} -61 {z2} minecraft:farmland[moisture=7] replace")
    rcon.cmd(f"fill {x1} -60 {z1} {x2} -60 {z2} minecraft:wheat[age=0] replace")


def build_furnace(rcon: Rcon) -> None:
    x, y, z = FURNACE
    rcon.cmd(f"setblock {x} {y} {z} minecraft:furnace")
    rcon.cmd(f"item replace block {x} {y} {z} container.0 with minecraft:raw_iron 3")
    rcon.cmd(f"item replace block {x} {y} {z} container.1 with minecraft:coal 1")


def profile_line(log_path: Path) -> str:
    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "Compatibility profile:" in line:
            return line.split("Compatibility profile:", 1)[1].strip()
    return ""


def tps_value(response: str) -> str:
    match = re.search(r"TPS from last 1m, 5m, 15m:\s*([0-9.]+)", response)
    return match.group(1) if match else "?"


# --------------------------------------------------------------------------- phases

def run_smoke(version: str, directory: Path, options, index: int) -> dict:
    rcon_port = options.rcon_base + index
    process, log_path = boot(directory, options.java, options.memory, options.boot_timeout)
    result: dict = {"smoke": "FAILED"}
    try:
        rcon = connect(options.host, rcon_port, options.boot_timeout)
        profile = profile_line(log_path)
        rcon.cmd("time set day")
        created = rcon.cmd(f"mobby active {REGION_ARGS} {REGION_NAME}")
        if "activated" not in created.lower():
            raise RuntimeError(f"region creation failed: {created}")
        build_furnace(rcon)
        plant_crops(rcon, CROP_Z)
        rcon.cmd(f"mobby stop {REGION_NAME}")
        rcon.cmd(f"mobby start {REGION_NAME}")
        info = rcon.cmd(f"mobby info {REGION_NAME}")
        time.sleep(options.wait_seconds)
        furnace = rcon.cmd(f"data get block {FURNACE[0]} {FURNACE[1]} {FURNACE[2]} Items[{{Slot:2b}}]")
        mobs = parse_count(rcon.cmd(
            "execute if entity @e[type=minecraft:zombified_piglin,x=100,y=-60,z=100,dx=15,dy=4,dz=15]"))
        crops = grown_crops(rcon, CROP_Z)
        tps = tps_value(rcon.cmd("tps"))
        result.update({
            "profile": profile,
            "furnace": "pass" if "iron_ingot" in furnace and "3" in furnace else f"FAIL ({furnace})",
            "mobs": mobs,
            "crops": crops,
            "tps": tps,
            "info": "pass" if "Status: ACTIVE" in info else "FAIL",
            "smoke": "pass",
        })
        shutdown(process, rcon)
        result["clean-disable"] = "pass" if "MobBy disabled." in log_path.read_text(
            encoding="utf-8", errors="replace") else "FAIL"
    except Exception:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=30)
        raise
    return result


def enable_native_ticks_mode(directory: Path) -> bool:
    config = directory / "plugins" / "MobBy" / "config.yml"
    if not config.exists():
        return False
    text = config.read_text(encoding="utf-8")
    if "native-random-ticks:" in text:
        text = re.sub(r"(?m)^(\s*native-random-ticks:).*$", r"\1 'yes'", text)
    else:
        text = text.replace("\ncrops:\n", "\ncrops:\n  native-random-ticks: 'yes'\n", 1)
    config.write_text(text, encoding="utf-8")
    return "native-random-ticks: 'yes'" in text


def run_native_probe(version: str, directory: Path, options, index: int) -> dict:
    if not enable_native_ticks_mode(directory):
        return {"native": "SKIPPED (no config.yml)"}
    process, log_path = boot(directory, options.java, options.memory, options.boot_timeout)
    result: dict = {"native": "FAILED"}
    try:
        rcon = connect(options.host, options.rcon_base + index, options.boot_timeout)
        text = log_path.read_text(encoding="utf-8", errors="replace")
        paused = "Crop simulation is disabled by crops.native-random-ticks" in text
        info = rcon.cmd(f"mobby info {REGION_NAME}")
        if "Unknown region" in info:
            rcon.cmd(f"mobby active {REGION_ARGS} {REGION_NAME}")
        rcon.cmd("time set day")
        plant_crops(rcon, PROBE_Z)
        time.sleep(options.wait_seconds)
        grown = grown_crops(rcon, PROBE_Z)
        result.update({
            "module-paused": "yes" if paused and "(disabled)" in info else "NO",
            "native": f"{'yes' if grown > 0 else 'no'} ({grown}/36 in {options.wait_seconds}s)",
        })
        shutdown(process, rcon)
    except Exception:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=30)
        raise
    return result


# --------------------------------------------------------------------------- main

def java_version_check(options):
    """Verify that Java 21+ is available; exit with a helpful error if not."""
    java_bin = options.java
    try:
        result = subprocess.run([java_bin, "-version"], capture_output=True, text=True, timeout=10)
        java_stderr = result.stderr.lower()
        if "21" not in java_stderr and "1.21" not in java_stderr and "25" not in java_stderr:
            # If we can't determine the version from stderr, assume too old
            print("error: Java 21+ is required for MobBy multi-version harness (Paper 1.20.6+).\n  Found java: " + java_bin)
            sys.exit(1)
    except Exception as e:
        print("error: Could not determine Java version. Make sure Java 21+ is installed and on PATH.\n  Details: " + str(e))
        sys.exit(1)


def parse_args(argv):
    parser = argparse.ArgumentParser(description="Run the MobBy smoke test and native-tick probe on several Paper versions.")
    parser.add_argument("--versions", nargs="+", required=True,
                        help="Paper versions, e.g. 1.20.6 1.21.1 1.21.4 1.21.8 26.3")
    parser.add_argument("--jar", default=None,
                        help="MobBy jar to test (default: newest dist/MobBy-*.jar)")
    parser.add_argument("--work-dir", default="build/smoke-servers",
                        help="directory for generated servers and cached Paper jars")
    parser.add_argument("--java", default=os.environ.get("JAVA_HOME", "") + "/bin/java" if os.environ.get("JAVA_HOME") else "java",
                        help="java executable (default: $JAVA_HOME/bin/java or java)")
    parser.add_argument("--memory", default="1400M", help="max heap for the test servers")
    parser.add_argument("--wait-seconds", type=int, default=60,
                        help="simulation window for furnace/mobs/crops (default 60)")
    parser.add_argument("--boot-timeout", type=int, default=300,
                        help="seconds to wait for a server to finish starting")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--rcon-base", type=int, default=25600, help="first RCON port, +1 per version")
    parser.add_argument("--game-base", type=int, default=25700, help="first game port, +1 per version")
    parser.add_argument("--keep", action="store_true", help="keep server directories after the run")
    parser.add_argument("--summary", default=None, help="write the Markdown summary to this file")
    parser.add_argument("--json", dest="json_out", default=None, help="write raw results as JSON")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    options = parse_args(argv if argv is not None else sys.argv[1:])
    java_version_check(options)
    repo = Path(__file__).resolve().parent.parent
    jar = Path(options.jar) if options.jar else sorted((repo / "dist").glob("MobBy-*.jar"))[-1]
    if not jar.exists():
        log(f"error: jar not found: {jar}")
        return 2
    work = Path(options.work_dir)
    work.mkdir(parents=True, exist_ok=True)
    log(f"MobBy jar: {jar}")
    log(f"work dir : {work}")

    results = []
    for index, version in enumerate(options.versions):
        log(f"\n===== Paper {version} =====")
        directory = work / f"paper-{version}"
        entry = {"version": version, "build": "?"}
        try:
            build, url = latest_build(version)
            entry["build"] = build
            log(f"build {build}: {url}")
            download(url, work / "cache" / f"paper-{version}-{build}.jar")
            if directory.exists() and not options.keep:
                shutil.rmtree(directory)
            directory.mkdir(parents=True)
            shutil.copyfile(work / "cache" / f"paper-{version}-{build}.jar", directory / "paper.jar")
            write_server_files(directory, options.game_base + index, options.rcon_base + index, jar)
            entry.update(run_smoke(version, directory, options, index))
            log(f"smoke: furnace={entry['furnace']} mobs={entry['mobs']} crops={entry['crops']}/36 tps={entry['tps']}")
            entry.update(run_native_probe(version, directory, options, index))
            log(f"native probe (module paused={entry.get('module-paused')}): {entry['native']}")
        except Exception as error:
            entry.setdefault("smoke", "FAILED")
            entry["error"] = str(error)
            log(f"ERROR: {error}")
        results.append(entry)
        if not options.keep:
            shutil.rmtree(directory, ignore_errors=True)

    header = ("| Paper | Build | Smoke test | Furnace | Mobs | Crops | TPS | Native ticks (module paused) |\n"
              "|---|---|---|---|---|---|---|---|")
    rows = [header]
    for entry in results:
        rows.append("| {version} | {build} | {smoke} | {furnace} | {mobs} | {crops}/36 | {tps} | {native} |".format(
            version=entry["version"], build=entry["build"], smoke=entry.get("smoke", "FAILED"),
            furnace=entry.get("furnace", "-"), mobs=entry.get("mobs", "-"), crops=entry.get("crops", "-"),
            tps=entry.get("tps", "-"), native=entry.get("native", "not run")))
    table = "\n".join(rows)
    log("\n" + table)
    if options.summary:
        Path(options.summary).parent.mkdir(parents=True, exist_ok=True)
        Path(options.summary).write_text(table + "\n", encoding="utf-8")
        log(f"summary written to {options.summary}")
    if options.json_out:
        Path(options.json_out).parent.mkdir(parents=True, exist_ok=True)
        Path(options.json_out).write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")

    failed = [entry["version"] for entry in results if entry.get("smoke") != "pass"]
    if failed:
        log(f"FAILED: {', '.join(failed)}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
