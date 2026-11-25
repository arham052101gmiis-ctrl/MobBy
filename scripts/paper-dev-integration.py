#!/usr/bin/env python3
"""Run the MobBy 1.1.0 functional, restart and optional 15-minute reliability suites in a new world."""
from __future__ import annotations

import argparse
import hashlib
import os
import secrets
import tempfile
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
PLUGIN_VERSION = "1.1.0"
PAPER_SHA256 = "c86baf83b1fb0eb0d572f39484c242210896c6adf9cc9342d5c16c4de70c9bdc"
RCON_PASSWORD = secrets.token_hex(24)


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
    def __init__(self, journal: Path):
        self.journal = journal
        self.rows = []
        self.failures = []

    def check(self, name: str, condition: bool, detail: str = "") -> bool:
        print(f"[{'PASS' if condition else 'FAIL'}] {name}" + (f" ({detail})" if detail else ""),
              flush=True)
        self.rows.append({"check": name, "status": "PASS" if condition else "FAIL", "detail": detail})
        with self.journal.open("a", encoding="utf-8") as stream:
            print(json.dumps(dict(time=time.time(), **self.rows[-1])), file=stream)
        if not condition:
            self.failures.append(name)
        return condition


def count_entities(rcon: Rcon, selector: str, dimension: str | None = None) -> int:
    """Entity count through a scoreboard, because newer servers print no Count line.

    Entity selectors are resolved in the executor's dimension, so nether checks must be run
    inside the nether or they silently count zero.
    """
    prefix = f"execute in minecraft:{dimension} run " if dimension else ""
    rcon.cmd("scoreboard players set #count mobbycount 0")
    rcon.cmd(f"{prefix}execute store result score #count mobbycount if entity {selector}")
    match = re.search(r"has (-?\d+)", rcon.cmd("scoreboard players get #count mobbycount"))
    if not match:
        raise RuntimeError("Unreadable scoreboard count for " + selector)
    return int(match.group(1))


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
  default-mob-type: NATURAL
  mob-caps:
    ZOMBIFIED_PIGLIN: 12
    COW: 2
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
    GoldFarmEgg: ZOMBIFIED_PIGLIN
    Pen: COW
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
network-compression-threshold=-1
server-ip=127.0.0.1
pause-when-empty-seconds=-1
server-port={game_port}
level-name=world
level-seed=42
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
enable-rcon=true
rcon.port={rcon_port}
rcon.password={password}
allow-nether=true
difficulty=normal
spawn-monsters=true
max-players=5
"""


def write_server(directory: Path, plugin_jar: Path, game_port: int, rcon_port: int) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (directory / "server.properties").write_text(
        SERVER_PROPERTIES.format(game_port=game_port, rcon_port=rcon_port, password=RCON_PASSWORD), encoding="utf-8")
    # Paper 26.3 uses the offline UUID when it checks the test client's whitelist entry.
    (directory / "whitelist.json").write_text(json.dumps([
        {"uuid": "cfaa4b11-c430-3355-8ea6-0611b408b8a6", "name": "MobByObserver"}
    ]), encoding="utf-8")
    (directory / "spigot.yml").write_text(SPIGOT_CONFIG, encoding="utf-8")
    plugins = directory / "plugins"
    plugins.mkdir(exist_ok=True)
    shutil.copyfile(plugin_jar, plugins / plugin_jar.name)
    config_dir = plugins / "MobBy"
    config_dir.mkdir(exist_ok=True)
    (config_dir / "config.yml").write_text(DEFAULT_CONFIG, encoding="utf-8")


def boot(directory: Path, java: str, memory: str, timeout: int):
    log_path = directory / f"console-{time.time_ns()}.log"
    with open(log_path, "wb") as handle:
        process = subprocess.Popen(
            [java, "-Xms512M", f"-Xmx{memory}", "-XX:+UseG1GC", "-jar", "paper.jar", "--nogui", "--nojline", "--noconsole"],
            cwd=directory, stdout=handle, stderr=subprocess.STDOUT)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"server exited during startup, see {log_path}")
        if "Done (" in log_path.read_text(encoding="utf-8", errors="replace"):
            return process, log_path
        time.sleep(1)
    process.kill()
    process.wait()
    raise RuntimeError(f"server did not start within {timeout}s, see {log_path}")


def connect(port: int, timeout: int) -> Rcon:
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            return Rcon("127.0.0.1", port, RCON_PASSWORD)
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
        process.kill()
        process.wait()
        raise RuntimeError("Paper shutdown exceeded 120 seconds")
    if process.returncode != 0:
        raise RuntimeError(f"Paper exited with code {process.returncode}")
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

    reply = rcon.cmd("mobby active 800 180 800 819 189 809 Limit")
    report.check("exactly 2000 blocks accepted", "Created and activated" in reply and "2000 blocks" in reply, reply)
    rcon.cmd("mobby remove Limit")
    rcon.cmd("mobby remove Limit confirm")
    reply = rcon.cmd("mobby active 800 180 800 822 182 828 TooBigOne")
    report.check("2001 blocks rejected", "exceeds the limit" in reply, reply)

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

    # Measure every managed piglin belonging to this farm, including any that wandered
    # outside its walls. Adjacent egg-farm piglins have a different persistent region key.
    farm_owner = 'type=zombified_piglin,nbt={BukkitValues:{"mobby:region":"goldfarm"}}'
    piglins = count_entities(rcon, f"@e[{farm_owner}]", "the_nether")
    on_magma = count_entities(rcon, f"@e[{farm_owner},x=101,y=128,z=101,dx=4,dy=1,dz=4]", "the_nether")
    report.check("zombified piglins spawned on nether roof magma", piglins > 0,
                 f"{piglins} piglins in the walled farm")
    report.check("no zombified piglin spawned on bedrock", piglins > 0 and on_magma == piglins,
                 f"{on_magma}/{piglins} piglins stand on magma, the rest on the bedrock rim")

    items = rcon.cmd("data get block 501 200 500 Items")
    report.check("furnace smelted unattended", "iron_ingot" in items and "count: 3" in items,
                 items[:150])
    # Pull the observed smelted output into a chest with a downward hopper.
    rcon.cmd("setblock 501 198 500 chest")
    rcon.cmd("setblock 501 199 500 hopper[facing=down]")
    time.sleep(3)
    output = rcon.cmd("data get block 501 198 500 Items")
    report.check("automated smelter delivered three ingots to chest", "iron_ingot" in output and "count: 3" in output, output)
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
    report.check("chunk tickets held while active", positive_tickets(farm_info),
                 farm_info.split("Chunks:")[-1][:70])
    report.check("Paper reports actual farm ticket", "held=true" in rcon.cmd("mobbytestticket"))
    rcon.cmd("mobby stop Farm")
    report.check("Paper confirms actual farm ticket release", "held=false" in rcon.cmd("mobbytestticket"))
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
    rcon = connect_owned(process, args.rcon_port, args.boot_timeout)
    try:
        listing = rcon.cmd("mobby list")
        report.check("regions resumed after restart", "MobBy Regions (4)" in listing, listing[:120])
        report.check("resumed regions are ACTIVE", listing.count("ACTIVE") >= 4,
                     f"{listing.count('ACTIVE')} ACTIVE entries")
        info = rcon.cmd("mobby info GoldFarm")
        report.check("resumed region has chunk tickets",
                     positive_tickets(info))
        report.check("resumed uptime is kept", "Total uptime" in info)
    finally:
        log = shutdown(process, rcon, log_path)
    report.check("clean shutdown after restart", "MobBy disabled." in log)


def positive_tickets(info):
    return re.search(r"\b[1-9]\d* loaded with MobBy chunk tickets", info) is not None


def connect_owned(process, port, timeout):
    try:
        return connect(port, timeout)
    except BaseException:
        process.kill()
        process.wait()
        raise


def extra_farms(rcon, report, work):
    # Apply a temporary filter only to the generated test server. Keep the geometry
    # and biome identical when switching to the positive-control phase below.
    config = work / "plugins" / "MobBy" / "config.yml"
    settings = config.read_text(encoding="utf-8")
    if "blocked-biomes: []" not in settings:
        raise RuntimeError("Expected generated biome-filter configuration")
    config.write_text(settings.replace("blocked-biomes: []", "blocked-biomes: [nether_wastes]"),
                      encoding="utf-8")
    report.check("biome filter configuration reloaded", "Configuration reloaded" in rcon.cmd("mobby reload"))
    for name, corners, world in [
            ("Pen", "600 199 600 608 204 608", ""),
            ("GhastOpen", "704 158 704 714 166 714", " world_nether")]:
        reply = rcon.cmd(f"mobby active {corners} {name}{world}")
        report.check(f"{name} activated", "Created and activated" in reply, reply)
    rcon.cmd("fill 600 199 600 608 204 608 glass hollow")
    rcon.cmd("fill 601 199 601 607 199 607 grass_block")
    rcon.cmd("time set noon")
    rcon.cmd("gamerule doDaylightCycle false")
    nether = "execute in minecraft:the_nether run "
    rcon.cmd(nether + "fill 704 158 704 714 158 714 netherrack")
    rcon.cmd(nether + "fill 704 159 704 714 166 714 air")
    rcon.cmd(nether + "fillbiome 704 158 704 714 166 714 minecraft:nether_wastes")
    report.check("native selection fixture is Nether wastes",
                 "Test passed" in rcon.cmd(nether + "execute if biome 708 159 708 minecraft:nether_wastes"))
    for name in ("Pen", "GhastOpen"):
        for cycle in range(4):
            rcon.cmd("mobby stop " + name)
            report.check(f"{name} stop {cycle + 1} released tickets",
                         "0 loaded with MobBy chunk tickets" in rcon.cmd("mobby info " + name))
            rcon.cmd("mobby start " + name)
            report.check(f"{name} start {cycle + 1} restored tickets",
                         positive_tickets(rcon.cmd("mobby info " + name)))

    blocked_selector = "@e[tag=mobby,x=704,y=158,z=704,dx=10,dy=8,dz=10]"
    blocked_count = 0
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        blocked_count = max(blocked_count, count_entities(rcon, blocked_selector, "the_nether"))
        time.sleep(2)
    report.check("biome exclusion suppresses managed spawns in the active Nether room",
                 blocked_count == 0, f"peak observed count {blocked_count}")
    report.check("positive control probe enabled", "Ghast positive control enabled" in rcon.cmd("mobbytestghast"))
    config.write_text(settings, encoding="utf-8")
    report.check("biome filter configuration reloaded", "Configuration reloaded" in rcon.cmd("mobby reload"))

    # Seed two adult cows, distinct from the tagged cows the passive profile must create.
    for x in (603, 604):
        rcon.cmd(f"summon cow {x} 200 603")
    deadline = time.monotonic() + 300
    passive = baby = ghast = False
    while time.monotonic() < deadline and not (passive and baby and ghast):
        passive |= count_entities(rcon, "@e[type=cow,tag=mobby,x=600,y=199,z=600,dx=8,dy=5,dz=8]") > 0
        age = rcon.cmd("execute as @e[type=cow,tag=!mobby,x=600,y=199,z=600,dx=8,dy=5,dz=8] run data get entity @s Age")
        baby |= bool(re.search(r": -\d+", age))
        ghast |= count_entities(rcon, "@e[type=ghast,tag=mobby,x=698,y=147,z=698,dx=24,dy=30,dz=24]",
                                "the_nether") > 0
        time.sleep(2)
    report.check("unattended passive profile spawned managed cows", passive)
    report.check("cow breeding produced a juvenile", baby)
    report.check("native Nether biome selection produced a managed ghast", ghast)
    report.check("managed native ghast is aware", "aware=true" in rcon.cmd("mobbytestaware"))
    report.check("pre-spawn veto exercised", "Cancelled non-ghast pre-spawn in positive control"
                 in (work / "logs/latest.log").read_text())
    other = count_entities(rcon, "@e[type=!ghast,tag=mobby,x=704,y=158,z=704,dx=10,dy=8,dz=10]", "the_nether")
    report.check("cancelled native candidates excluded", other == 0, f"{other} observed")
    for name in ("GhastOpen", "Pen"):
        rcon.cmd("mobby remove " + name)
        rcon.cmd("mobby remove " + name + " confirm")
        report.check(name + " removed", "Unknown region" in rcon.cmd("mobby info " + name))


def profile_matrix(rcon, report, work):
    config = work / "plugins/MobBy/config.yml"
    original = config.read_text()
    # For seed 42, chunk (42, 0) is a slime chunk and (27, 0) is not.
    regions = [("SlimeRoom", "672 19 0 680 25 8"), ("NoSlime", "432 19 0 440 25 8"), ("Pasture", "600 199 600 608 204 608")]
    for name, corners in regions:
        report.check(name + " fixture active", "Created and activated" in rcon.cmd(f"mobby active {corners} {name}"))
    rcon.cmd("fill 672 19 0 680 25 8 stone hollow")
    rcon.cmd("fill 432 19 0 440 25 8 stone hollow")
    rcon.cmd("fill 600 199 600 608 204 608 glass hollow")
    rcon.cmd("fill 601 199 601 607 199 607 grass_block")
    rcon.cmd("time set noon")
    dark = ["ZOMBIE_VILLAGER", "HUSK", "SKELETON", "STRAY", "CREEPER", "WITCH", "ENDERMAN", "SPIDER", "CAVE_SPIDER"]
    light = ["SHEEP", "PIG", "CHICKEN", "RABBIT", "HORSE", "DONKEY", "LLAMA"]
    try:
        for index, hostile in enumerate(dark):
            pasture = light[min(index, len(light) - 1)]
            for area in ["x=100,y=200,z=100,dx=8,dy=6,dz=8", "x=600,y=199,z=600,dx=8,dy=5,dz=8"]:
                rcon.cmd(f"kill @e[tag=mobby,{area}]")
            config.write_text(original.replace("DarkRoom: ZOMBIE",
                f"DarkRoom: {hostile}\n    Pasture: {pasture}\n    SlimeRoom: SLIME\n    NoSlime: SLIME"))
            rcon.cmd("mobby reload")
            subjects = [(hostile, "x=100,y=200,z=100,dx=8,dy=6,dz=8")]
            if index < len(light):
                subjects.append((pasture, "x=600,y=199,z=600,dx=8,dy=5,dz=8"))
            if index == 0:
                subjects.append(("SLIME", "x=672,y=19,z=0,dx=8,dy=6,dz=8"))
            for species, area in subjects:
                selector = f"@e[type={species.lower()},tag=mobby,{area}]"
                deadline = time.monotonic() + 20
                count = count_entities(rcon, selector)
                while not count and time.monotonic() < deadline:
                    time.sleep(1)
                    count = count_entities(rcon, selector)
                report.check("managed profile " + species, count > 0, f"{count} observed")
        count = count_entities(rcon, "@e[type=slime,tag=mobby,x=432,y=19,z=0,dx=8,dy=6,dz=8]")
        report.check("slimes excluded from non-slime chunk", count == 0, f"{count} observed")
    finally:
        config.write_text(original)
        rcon.cmd("mobby reload")
        for name, _ in regions:
            rcon.cmd("mobby remove " + name)
            rcon.cmd("mobby remove " + name + " confirm")


def distant_player(rcon, report, port):
    client = subprocess.Popen([sys.executable, str(Path(__file__).with_name("paper-test-client.py")), str(port)])
    try:
        deadline = time.monotonic() + 25
        while "MobByObserver" not in rcon.cmd("list"):
            if client.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError("Loopback test client failed to join")
        rcon.cmd("gamemode creative MobByObserver")
        rcon.cmd("tp MobByObserver 1200 200 1200")
        distant = "execute positioned 104 203 104 if entity @a[name=MobByObserver,distance=1000..]"
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and "Test passed" not in rcon.cmd(distant):
            time.sleep(0.25)
        report.check("observer online over 1000 blocks from chamber", "Test passed" in rcon.cmd(distant),
                     rcon.cmd("data get entity MobByObserver Pos"))
        selector = "@e[type=zombie,tag=mobby,x=100,y=201,z=100,dx=8,dy=5,dz=8]"
        # Tag all existing subjects. Only a newly spawned managed zombie can satisfy the check.
        rcon.cmd("tag " + selector + " add preobserver")
        rcon.cmd("kill " + selector)
        new = selector[:-1] + ",tag=!preobserver]"
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline and not count_entities(rcon, new):
            time.sleep(1)
        report.check("new managed zombie while sole player is distant", count_entities(rcon, new) > 0
                     and "Test passed" in rcon.cmd(distant) and "1 of a max" in rcon.cmd("list"))
    finally:
        client.terminate()
        client.wait(timeout=10)


def check_logs(paths, report):
    for path in paths:
        log = path.read_text(encoding="utf-8", errors="replace")
        bad = [line for line in log.splitlines() if any(pattern in line for pattern in (
            "Could not change mob awareness", "Native spawning unavailable", "Could not spawn",
            "failed in region", "Could not pass event", "Error occurred while enabling MobBy"))]
        report.check("no plugin errors in " + path.name, not bad, " | ".join(bad[:5]))


def start_cpu_recording(jcmd, pid):
    subprocess.run([str(jcmd), str(pid), "JFR.start", "name=MobByCpu", "settings=none",
                    "+jdk.CPULoad#enabled=true", "+jdk.CPULoad#period=1s"],
                   capture_output=True, text=True, timeout=30, check=True)


def save_cpu_samples(jcmd, pid, evidence):
    # JFR runs inside the target JVM. Use an absolute output path for the server's working directory.
    recording = (evidence / "server-cpu.jfr").resolve()
    subprocess.run([str(jcmd), str(pid), "JFR.stop", "name=MobByCpu", f'filename="{recording}"'],
                   capture_output=True, text=True, timeout=30, check=True)
    jfr = jcmd.with_name("jfr.exe" if os.name == "nt" else "jfr")
    result = subprocess.run([str(jfr), "print", "--json", "--events", "jdk.CPULoad", str(recording)],
                            capture_output=True, text=True, timeout=60, check=True)
    events = json.loads(result.stdout)["recording"]["events"]
    if not events:
        raise RuntimeError("JFR contained no process CPU samples")
    with (evidence / "cpu.jsonl").open("x", encoding="utf-8") as out:
        for event in events:
            values = event["values"]
            out.write(json.dumps(dict(time=values["startTime"], jvm_user=values["jvmUser"],
                                      jvm_system=values["jvmSystem"], machine_total=values["machineTotal"])) + "\n")


def reliability(rcon, report, java, pid, evidence):
    jcmd = java.with_name("jcmd.exe" if os.name == "nt" else "jcmd")
    start_cpu_recording(jcmd, pid)
    started = time.monotonic()
    try:
        with (evidence / "performance.jsonl").open("x", encoding="utf-8") as samples:
            while True:
                listing = rcon.cmd("list")
                tps = rcon.cmd("tps")
                mspt = rcon.cmd("mspt")
                heap = subprocess.run([str(jcmd), str(pid), "GC.heap_info"], capture_output=True,
                                      text=True, timeout=20, check=True).stdout
                elapsed = time.monotonic() - started
                farm = rcon.cmd("mobby info GoldFarm")
                report.check("unattended gold farm active during sampling", positive_tickets(farm)
                             and bool(re.search(r"\b[1-9]\d* MobBy mob", farm)) and "unattended" in farm)
                sample = dict(gold_farm=farm, time=time.time(), elapsed_seconds=elapsed, players=listing, tps=tps, mspt=mspt, heap=heap)
                print(json.dumps(sample), file=samples, flush=True)
                report.check("sampling with zero online", bool(re.search(r"\b0 of a max", listing)), listing)
                report.check("TPS, tick times and heap available", bool(re.search(r"\d+\.\d+", tps))
                             and bool(re.search(r"\d+\.\d+", mspt)) and "used" in heap, tps)
                if elapsed >= 900:
                    break
                time.sleep(min(30, 900 - elapsed))
    finally:
        save_cpu_samples(jcmd, pid, evidence)


def digest(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--paper-jar", type=Path, required=True)
    parser.add_argument("--plugin-jar", type=Path, default=Path("build/libs/MobBy-1.1.0.jar"))
    parser.add_argument("--probe-jar", type=Path, default=Path("build/paper-fixtures/MobBy-test-probe.jar"))
    parser.add_argument("--java", default=None)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--short-reliability", action="store_true")
    parser.add_argument("--game-port", type=int, default=25900)
    parser.add_argument("--rcon-port", type=int, default=25975)
    parser.add_argument("--memory", default="1400M")
    parser.add_argument("--boot-timeout", type=int, default=300)
    args = parser.parse_args()
    home = os.environ.get("JAVA_HOME")
    binary = "java.exe" if os.name == "nt" else "java"
    args.java = shutil.which(args.java or (str(Path(home) / "bin" / binary) if home else binary))
    if not args.java:
        parser.error("Supply --java or JAVA_HOME pointing to JDK 25")
    java_version = subprocess.check_output([args.java, "-version"], stderr=subprocess.STDOUT, text=True)
    if not re.search(r'version "25[."]', java_version):
        parser.error("This target requires Java 25")
    if digest(args.paper_jar) != PAPER_SHA256:
        parser.error("Paper jar differs from the handoff fingerprint for 26.3 build 256")
    plugin_hash = digest(args.plugin_jar)
    probe_hash = digest(args.probe_jar)
    if args.game_port == args.rcon_port:
        parser.error("Game and RCON ports must differ")
    for port in (args.game_port, args.rcon_port):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    args.evidence.mkdir(parents=True, exist_ok=False)
    report = Report(args.evidence / "checks.jsonl")
    metadata = dict(java=java_version, paper_sha256=PAPER_SHA256, plugin_sha256=plugin_hash, probe_sha256=probe_hash)
    with tempfile.TemporaryDirectory(prefix="mobby-dev1-") as temp:
        work = Path(temp)
        try:
            shutil.copyfile(args.paper_jar, work / "paper.jar")
            write_server(work, args.plugin_jar, args.game_port, args.rcon_port)
            shutil.copyfile(args.probe_jar, work / "plugins" / "MobBy-test-probe.jar")
            process, log_path = boot(work, args.java, args.memory, args.boot_timeout)
            rcon = connect_owned(process, args.rcon_port, args.boot_timeout)
            try:
                startup = log_path.read_text(encoding="utf-8", errors="replace")
                for case in ("null", "dead", "invalid"):
                    report.check("entity guard " + case, f"[MobByTestProbe] Entity guard passed: {case}" in startup)
                report.check("target server version", "This server is running Paper version 26.3-256-" in startup)
                report.check("zero players at startup", "0 of a max" in rcon.cmd("list"))
                run_tests(rcon, report)
                extra_farms(rcon, report, work)
                profile_matrix(rcon, report, work)
                distant_player(rcon, report, args.game_port)
            finally:
                log = shutdown(process, rcon, log_path)
            report.check("clean plugin shutdown", "MobBy disabled." in log)
            stored = (work / "plugins/MobBy/regions.yml").read_text()
            report.check("four active regions persisted", "GoldFarm" in stored and stored.count("ACTIVE") == 4)
            run_restart_check(work, args, report)
            check_logs([*work.glob("console-*.log"), work / "logs" / "latest.log"], report)
            if args.short_reliability and not report.failures:
                process, log_path = boot(work, args.java, args.memory, args.boot_timeout)
                rcon = connect_owned(process, args.rcon_port, args.boot_timeout)
                try:
                    if "MobBy Regions (4)" not in rcon.cmd("mobby list") or not positive_tickets(rcon.cmd("mobby info GoldFarm")):
                        raise RuntimeError("Expected persisted workload missing on reliability boot")
                    reliability(rcon, report, Path(args.java), process.pid, args.evidence)
                finally:
                    log = shutdown(process, rcon, log_path)
                report.check("clean reliability shutdown", "MobBy disabled." in log)
                check_logs([log_path, work / "logs" / "latest.log"], report)
        except (Exception, KeyboardInterrupt) as error:
            report.check("suite completed", False, repr(error))
        finally:
            for path in [*work.glob("console-*.log"), *work.glob("logs/*.log*")]:
                shutil.copyfile(path, args.evidence / path.name)
            (args.evidence / "results.json").write_text(
                json.dumps(dict(**metadata, checks=report.rows), indent=2) + "\n", encoding="utf-8")
    return int(bool(report.failures))


if __name__ == "__main__":
    sys.exit(main())
