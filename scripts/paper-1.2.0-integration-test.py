#!/usr/bin/env python3
"""MobBy 1.2.0 integration test against a real Paper 26.3 server with zero players.

The script owns the whole server lifecycle, so it is one command:

    python3 scripts/paper-1.2.0-integration-test.py --paper-jar ~/paper-26.3-253.jar --java ~/jdk25/bin/java

What it verifies, with no player connected:

* plugin version reported by /mobby help and the server plugin list,
* region creation, validation, activation and chunk tickets,
* vanilla-like hostile spawning in a sealed dark chamber (block light 0),
* nether gold farm spawning: zombified piglins above magma, never above bedrock,
* turtle egg attraction: piglins walk to an egg placed away from the spawn platform,
* furnace smelting, crop growth and redstone piston firing while unattended,
* mobs carrying the MobBy persistent data tag,
* chunk ticket cleanup on /mobby stop, region persistence, clean shutdown.

Run with ``--restart-only`` afterwards (same ``--work-dir``, ``--keep`` on the first run) to verify
that regions resume as ACTIVE after a real server restart.

Only the Python standard library is used; the Paper jar is supplied by the caller.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

COLOR = re.compile("\u00a7[0-9a-fk-orx]")
PLUGIN_VERSION = "1.2.0"


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
        return struct.unpack("<i", data[:4])[0], data[8:-2].decode("utf-8", "replace")

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


class Report:
    def __init__(self):
        self.rows = []
        self.failures = []

    def check(self, name: str, condition: bool, detail: str = "") -> bool:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}" + (f" ({detail})" if detail else ""),
              flush=True)
        self.rows.append({"check": name, "status": "PASS" if condition else "FAIL", "detail": detail})
        if not condition:
            self.failures.append(name)
        return condition


def count_entities(rcon: Rcon, selector: str, dimension: str | None = None) -> int:
    """Entity count through a scoreboard, because newer servers print no Count line.

    Entity selectors are resolved in the executor's dimension, so nether checks must be run
    inside the nether or they silently count zero.
    """
    prefix = f"execute in minecraft:{dimension} run " if dimension else ""
    rcon.cmd(f"{prefix}execute store result score #count mobbycount if entity {selector}")
    match = re.search(r"has (-?\d+)", rcon.cmd("scoreboard players get #count mobbycount"))
    return int(match.group(1)) if match else 0


def int_in(text: str, pattern: str) -> int:
    match = re.search(pattern, text)
    return int(match.group(1)) if match else -1


# --------------------------------------------------------------------------- server files

DEFAULT_CONFIG = """\
limits:
  max-region-volume: 2000
  max-regions: 50
  max-active-regions: 10
  max-loaded-chunks: 64
  max-block-operations-per-tick: 200
storage:
  uptime-checkpoint-seconds: 60
  resume-active-regions-on-startup: true
  region-rescan-seconds: 10
simulation:
  skip-when-player-within-blocks: 96
modules:
  mob-spawning:
    enabled: true
  furnace:
    enabled: true
  redstone:
    enabled: true
  crops:
    enabled: true
  animals:
    enabled: true
mob-spawning:
  interval-ticks: 40
  max-mobs-per-region: 20
  max-mobs-per-cycle: 4
  max-entities-in-region: 80
  spawn-attempts: 24
  placement-attempts: 24
  only-when-unattended: true
  remove-mobs-on-stop: false
  remove-mobs-on-region-remove: true
  require-solid-ground: true
  max-light-level: 15
  default-mob-type: ZOMBIE
  mob-caps:
    ZOMBIFIED_PIGLIN: 12
    ZOMBIE: 12
    SKELETON: 6
    CREEPER: 6
    SPIDER: 6
    SLIME: 6
  slime-chunk-only: true
  nether:
    require-magma-surface: true
  turtle-egg-attraction: true
  turtle-egg-radius: 24.0
  turtle-egg-attracted-mobs:
    - ZOMBIFIED_PIGLIN
  pathfind-speed: 1.0
  keep-mobs-aware: true
  allowed-biomes: []
  blocked-biomes: []
  region-mob-types:
    DarkRoom: ZOMBIE
    GoldFarm: ZOMBIFIED_PIGLIN
furnace:
  interval-ticks: 20
  stall-passes-before-simulation: 3
  max-furnaces-per-pass: 32
  unknown-fuel-burn-ticks: 300
  always-simulate: false
  fuel-burn-ticks:
    COAL: 1600
    COAL_BLOCK: 16000
    BLAZE_ROD: 2400
    STICK: 100
redstone:
  explicit-tick-fallback: false
  max-components-per-pass: 64
crops:
  random-ticks-per-section: 3
  max-random-ticks-per-pass: 64
  native-random-ticks: auto
animals:
  interval-ticks: 600
  max-animals-per-region: 12
  max-breedings-per-cycle: 1
  max-pair-distance: 8.0
  love-mode-ticks: 600
debug: true
"""

SPIGOT_CONFIG = """\
world-settings:
  default:
    entity-activation-range:
      animals: 0
      monsters: 0
      raiders: 0
      misc: 0
      water: 0
      villagers: 0
      flying-monsters: 0
"""

SERVER_PROPERTIES = """\
online-mode=false
server-port={game_port}
level-name=world
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
enable-rcon=true
rcon.port={rcon_port}
rcon.password=mobby
allow-nether=true
difficulty=normal
spawn-monsters=true
max-players=5
"""


def write_server(directory: Path, plugin_jar: Path, game_port: int, rcon_port: int) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (directory / "server.properties").write_text(
        SERVER_PROPERTIES.format(game_port=game_port, rcon_port=rcon_port), encoding="utf-8")
    (directory / "spigot.yml").write_text(SPIGOT_CONFIG, encoding="utf-8")
    plugins = directory / "plugins"
    plugins.mkdir(exist_ok=True)
    shutil.copyfile(plugin_jar, plugins / plugin_jar.name)
    config_dir = plugins / "MobBy"
    config_dir.mkdir(exist_ok=True)
    (config_dir / "config.yml").write_text(DEFAULT_CONFIG, encoding="utf-8")


def boot(directory: Path, java: str, memory: str, timeout: int):
    log_path = directory / "console.log"
    handle = open(log_path, "wb")
    process = subprocess.Popen(
        [java, "-Xms512M", f"-Xmx{memory}", "-XX:+UseG1GC", "-jar", "paper.jar", "--nogui"],
        cwd=directory, stdout=handle, stderr=subprocess.STDOUT, start_new_session=True)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during startup, see {log_path}")
        if "Done (" in log_path.read_text(encoding="utf-8", errors="replace"):
            return process, log_path
        time.sleep(1)
    process.terminate()
    process.wait(timeout=30)
    raise RuntimeError(f"server did not start within {timeout}s, see {log_path}")


def connect(port: int, timeout: int) -> Rcon:
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            return Rcon("127.0.0.1", port, "mobby")
        except (OSError, RuntimeError) as error:
            last = error
            time.sleep(1)
    raise RuntimeError(f"RCON unavailable on port {port}: {last}")


def shutdown(process: subprocess.Popen, rcon: Rcon, log_path: Path) -> str:
    try:
        rcon.cmd("stop")
    except OSError:
        pass
    rcon.close()
    try:
        process.wait(timeout=120)
    except subprocess.TimeoutExpired:
        process.terminate()
        process.wait(timeout=30)
    return log_path.read_text(encoding="utf-8", errors="replace")


# --------------------------------------------------------------------------- full test

def run_tests(rcon: Rcon, report: Report) -> None:
    rcon.cmd("scoreboard objectives remove mobbycount")
    rcon.cmd("scoreboard objectives add mobbycount dummy")

    help_text = rcon.cmd("mobby help")
    report.check("help reports MobBy v" + PLUGIN_VERSION, f"MobBy v{PLUGIN_VERSION}" in help_text,
                 help_text[:110])
    report.check("server plugin list contains MobBy", "MobBy" in rcon.cmd("plugins"))
    profile = rcon.cmd("mobby help")
    report.check("mob AI capabilities reported", "Unattended mob AI" in profile,
                 profile[-140:])

    # ---------------------------------------------------------------- sealed dark chamber
    # Regions are created first so their chunk tickets load the chunks, then the farm is built,
    # then the region is restarted to re-run the block scan. Without this order the build commands
    # would target unloaded chunks on a server with nobody online.
    dark = rcon.cmd("mobby active 100 200 100 108 206 108 DarkRoom")
    report.check("dark room region created", "Created and activated" in dark, dark)
    time.sleep(2)
    rcon.cmd("fill 100 200 100 108 206 108 minecraft:stone")
    rcon.cmd("fill 101 201 101 107 205 107 minecraft:air")
    rcon.cmd("mobby stop DarkRoom")
    rcon.cmd("mobby start DarkRoom")
    dark_info = rcon.cmd("mobby info DarkRoom")
    floors = int_in(dark_info, r"(\d+) indexed floor")
    report.check("dark room floor index populated", floors > 0, f"{floors} indexed floors")
    report.check("2000 block volume limit enforced", "exceeds the limit" in
                 rcon.cmd("mobby active 0 -60 0 40 -20 40 TooBig"))

    # ---------------------------------------------------------------- nether gold farm
    # Two nether roof regions:
    #   GoldFarm    - walled magma platform on a bedrock roof, no egg. Tests the spawn surface rule:
    #                 piglins may only appear above the magma, never on the bedrock rim.
    #   GoldFarmEgg - an open magma platform plus a turtle egg ledge. Tests egg attraction; the
    #                 piglins legitimately walk across bedrock here, so it is a separate region.
    # Keep the region identical to the walled interior. A larger region leaves a bedrock strip
    # outside the walls, so a piglin can legitimately walk there and make the surface check flaky.
    gold = rcon.cmd("mobby active 100 127 100 106 129 106 GoldFarm world_nether")
    report.check("nether gold farm region created", "Created and activated" in gold, gold)
    time.sleep(2)
    nether = "execute in minecraft:the_nether run "
    rcon.cmd(nether + "fill 100 127 100 106 127 106 minecraft:bedrock")
    rcon.cmd(nether + "fill 101 127 101 105 127 105 minecraft:magma_block")
    rcon.cmd(nether + "fill 100 128 100 106 129 106 minecraft:air")
    # Two-block walls cover the full region perimeter and keep the piglins on the magma, so a
    # piglin above bedrock can only be a bad spawn rather than one that walked out.
    rcon.cmd(nether + "fill 100 128 100 100 129 106 minecraft:stone")
    rcon.cmd(nether + "fill 106 128 100 106 129 106 minecraft:stone")
    rcon.cmd(nether + "fill 101 128 100 105 129 100 minecraft:stone")
    rcon.cmd(nether + "fill 101 128 106 105 129 106 minecraft:stone")
    rcon.cmd("mobby stop GoldFarm")
    rcon.cmd("mobby start GoldFarm")

    egg_region = rcon.cmd("mobby active 110 127 110 118 131 118 GoldFarmEgg world_nether")
    report.check("turtle egg region created", "Created and activated" in egg_region, egg_region)
    time.sleep(2)
    rcon.cmd(nether + "fill 110 127 110 118 127 118 minecraft:bedrock")
    rcon.cmd(nether + "fill 111 127 111 115 127 115 minecraft:magma_block")
    rcon.cmd(nether + "fill 110 128 110 118 131 118 minecraft:air")
    # A 1x1 netherrack ledge with the turtle egg, beside the spawn platform.
    rcon.cmd(nether + "setblock 118 127 118 minecraft:netherrack")
    rcon.cmd(nether + "setblock 118 128 118 minecraft:turtle_egg")
    rcon.cmd("mobby stop GoldFarmEgg")
    rcon.cmd("mobby start GoldFarmEgg")
    egg_info = rcon.cmd("mobby info GoldFarmEgg")
    report.check("egg region indexes the turtle egg", "1 turtle egg(s)" in egg_info,
                 egg_info[-100:])

    # ---------------------------------------------------------------- overworld farm
    farm = rcon.cmd("mobby active 500 199 500 507 204 507 Farm")
    report.check("overworld farm region created", "Created and activated" in farm, farm)
    time.sleep(2)
    rcon.cmd("time set day")
    rcon.cmd("setblock 501 200 500 minecraft:furnace")
    rcon.cmd("item replace block 501 200 500 container.2 with minecraft:air")
    rcon.cmd("item replace block 501 200 500 container.0 with minecraft:raw_iron 3")
    rcon.cmd("item replace block 501 200 500 container.1 with minecraft:coal 1")
    rcon.cmd("fill 502 199 502 505 199 505 minecraft:farmland[moisture=7] replace")
    rcon.cmd("fill 502 200 502 505 200 505 minecraft:wheat[age=0] replace")
    rcon.cmd("setblock 505 200 507 minecraft:piston[facing=east]")
    rcon.cmd("setblock 504 200 507 minecraft:air replace")
    rcon.cmd("setblock 504 200 507 minecraft:redstone_block")
    rcon.cmd("mobby stop Farm")
    rcon.cmd("mobby start Farm")
    farm_info = rcon.cmd("mobby info Farm")
    report.check("farm block index finds the furnace and crops",
                 "1 furnace(s)" in farm_info and "16 crop(s)" in farm_info, farm_info[-130:])

    print("... unattended simulation running for 90 seconds", flush=True)
    time.sleep(90)

    zombies = count_entities(rcon, "@e[type=minecraft:zombie,x=100,y=201,z=100,dx=8,dy=5,dz=8]")
    dark_info = rcon.cmd("mobby info DarkRoom")
    report.check("hostile mobs spawned in the dark room without players", zombies > 0,
                 f"{zombies} zombies; {dark_info.split('Region contents:')[-1][:70]}")
    report.check("spawned mobs carry MobBy persistent data",
                 "mobby:managed" in rcon.cmd(
                     "data get entity @e[type=minecraft:zombie,x=100,y=201,z=100,dx=8,dy=5,dz=8,limit=1] BukkitValues"))

    piglins = count_entities(rcon, "@e[type=minecraft:zombified_piglin,x=100,y=128,z=100,dx=8,dy=1,dz=8]",
                             "the_nether")
    on_magma = count_entities(
        rcon, "@e[type=minecraft:zombified_piglin,x=101,y=128,z=101,dx=4,dy=1,dz=4]", "the_nether")
    report.check("zombified piglins spawned on nether roof magma", piglins > 0,
                 f"{piglins} piglins in the walled farm")
    report.check("no zombified piglin spawned on bedrock", piglins > 0 and on_magma == piglins,
                 f"{on_magma}/{piglins} piglins stand on magma, the rest on the bedrock rim")

    items = rcon.cmd("data get block 501 200 500 Items")
    report.check("furnace smelted unattended", "iron_ingot" in items and "count: 3" in items,
                 items[:150])
    report.check("redstone piston fired unattended",
                 "Test passed" in rcon.cmd("execute if block 506 200 507 minecraft:piston_head"))
    grown = 0
    for x in range(502, 506):
        for z in range(502, 506):
            present = "Test passed" in rcon.cmd(f"execute if block {x} 200 {z} minecraft:wheat")
            immature = "Test passed" in rcon.cmd(
                f"execute if block {x} 200 {z} minecraft:wheat[age=0]")
            if present and not immature:
                grown += 1
    report.check("crops grew unattended", grown > 0, f"{grown}/16 sampled crops above age 0")

    # ---------------------------------------------------------------- turtle egg attraction
    # The egg sits on its own ledge at 108,128,108; a piglin there must have walked.
    ledger_before = count_entities(
        rcon, "@e[type=minecraft:zombified_piglin,x=117,y=128,z=117,dx=1,dy=3,dz=1]", "the_nether")
    print("... waiting 25 seconds for turtle egg attraction", flush=True)
    time.sleep(25)
    ledger_after = count_entities(
        rcon, "@e[type=minecraft:zombified_piglin,x=117,y=128,z=117,dx=1,dy=3,dz=1]", "the_nether")
    report.check("turtle egg attraction pulls piglins to the egg ledge", ledger_after > 0,
                 f"{ledger_before} -> {ledger_after} piglins at the egg")
    near_egg = count_entities(
        rcon, "@e[type=minecraft:zombified_piglin,x=116,y=128,z=116,dx=2,dy=3,dz=2]", "the_nether")
    report.check("piglins moved off the spawn platform", near_egg > 0,
                 f"{near_egg} piglins within 2 blocks of the egg")

    # ---------------------------------------------------------------- tickets and lifecycle
    farm_info = rcon.cmd("mobby info Farm")
    report.check("chunk tickets held while active", "loaded with MobBy chunk tickets" in farm_info,
                 farm_info.split("Chunks:")[-1][:70])
    rcon.cmd("mobby stop Farm")
    stopped = rcon.cmd("mobby info Farm")
    report.check("chunk tickets released on stop", "0 loaded with MobBy chunk tickets" in stopped)
    report.check("stopped region has no simulation data", "not scanned" in stopped)
    restart = rcon.cmd("mobby start Farm")
    report.check("stopped region restarts", "is now active" in restart, restart)
    stop_reply = rcon.cmd("mobby stop DarkRoom")
    report.check("region stop reports paused uptime", "uptime is paused" in stop_reply, stop_reply)
    restart_dark = rcon.cmd("mobby start DarkRoom")
    report.check("dark room restarts", "is now active" in restart_dark, restart_dark)
    report.check("region list shows four regions", "MobBy Regions (4)" in rcon.cmd("mobby list"))
    rcon.cmd("mobby reload")
    report.check("reload keeps every region", "MobBy Regions (4)" in rcon.cmd("mobby list"))
    report.check("unknown region is rejected", "Unknown region" in rcon.cmd("mobby info Nope"))


def run_restart_check(work: Path, args, report: Report) -> None:
    process, log_path = boot(work, args.java, args.memory, args.boot_timeout)
    rcon = connect(args.rcon_port, args.boot_timeout)
    try:
        listing = rcon.cmd("mobby list")
        report.check("regions resumed after restart", "MobBy Regions (4)" in listing, listing[:120])
        report.check("resumed regions are ACTIVE", listing.count("ACTIVE") >= 4,
                     f"{listing.count('ACTIVE')} ACTIVE entries")
        info = rcon.cmd("mobby info GoldFarm")
        report.check("resumed region has chunk tickets",
                     "loaded with MobBy chunk tickets" in info)
        report.check("resumed uptime is kept", "Total uptime" in info)
    finally:
        log = shutdown(process, rcon, log_path)
    report.check("clean shutdown after restart", "MobBy disabled." in log)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--paper-jar", default=None)
    parser.add_argument("--plugin-jar", default="dist/MobBy-1.2.0.jar")
    parser.add_argument("--java", default="java")
    parser.add_argument("--work-dir", default="build/paper-it")
    parser.add_argument("--memory", default="1400M")
    parser.add_argument("--boot-timeout", type=int, default=300)
    parser.add_argument("--game-port", type=int, default=25600)
    parser.add_argument("--rcon-port", type=int, default=25675)
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--restart-only", action="store_true",
                        help="boot the existing work dir and verify restart recovery")
    parser.add_argument("--report", default=None, help="write a JSON report here")
    args = parser.parse_args()

    work = Path(args.work_dir).resolve()
    report = Report()

    if args.restart_only:
        if not (work / "paper.jar").is_file():
            print(f"error: no existing server in {work}")
            return 2
        run_restart_check(work, args, report)
    else:
        if not args.paper_jar:
            print("error: --paper-jar is required")
            return 2
        jar = Path(args.paper_jar).resolve()
        if not jar.is_file():
            print(f"error: Paper jar not found: {jar}")
            return 2
        if work.exists():
            shutil.rmtree(work)
        plugin_jar = Path(args.plugin_jar).resolve()
        if not plugin_jar.is_file():
            print(f"error: MobBy jar not found: {plugin_jar}")
            return 2
        work.mkdir(parents=True)
        shutil.copyfile(jar, work / "paper.jar")
        write_server(work, plugin_jar, args.game_port, args.rcon_port)
        process, log_path = boot(work, args.java, args.memory, args.boot_timeout)
        text = log_path.read_text(encoding="utf-8", errors="replace")
        report.check(f"MobBy v{PLUGIN_VERSION} enabled on Paper",
                     f"MobBy v{PLUGIN_VERSION} enabling" in text)
        report.check("compatibility profile logged", "Compatibility profile:" in text,
                     next((line.split("Compatibility profile:")[-1].strip()
                           for line in text.splitlines() if "Compatibility profile:" in line), ""))
        report.check("activation range 0 detected", "Entity activation range is disabled" in text)
        rcon = connect(args.rcon_port, args.boot_timeout)
        try:
            run_tests(rcon, report)
        finally:
            log = shutdown(process, rcon, log_path)
        report.check("clean shutdown", "MobBy disabled." in log)
        report.check("no MobBy errors in the server log",
                     "Could not spawn" not in log and "failed in region" not in log)

        regions_file = work / "plugins" / "MobBy" / "regions.yml"
        stored = regions_file.read_text(encoding="utf-8") if regions_file.is_file() else ""
        report.check("regions persisted to disk", "GoldFarm" in stored)
        report.check("regions left ACTIVE for restart recovery", stored.count("ACTIVE") == 4,
                     f"{stored.count('ACTIVE')} ACTIVE entries")

    print()
    print(f"{len(report.rows) - len(report.failures)}/{len(report.rows)} checks passed")
    if report.failures:
        print("failed: " + ", ".join(report.failures))
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report.rows, indent=2) + "\n", encoding="utf-8")
    if not args.keep:
        shutil.rmtree(work, ignore_errors=True)
    else:
        print(f"server directory kept at {work}")
    return 1 if report.failures else 0


if __name__ == "__main__":
    sys.exit(main())
