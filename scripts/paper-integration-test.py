#!/usr/bin/env python3
"""MobBy integration test against a running Paper 26.3 server over RCON.

Setup
-----
1. server.properties:
     enable-rcon=true
     rcon.port=25575
     rcon.password=change-me
     level-type=minecraft\\:flat
     online-mode=false
2. Start the server with MobBy installed, no players connected.
3. Run:  python3 scripts/paper-integration-test.py --password change-me

The script is deliberately dependency free (standard library only). It builds a small farm in a
flat world, exercises every command, and asserts the simulation results. It is a smoke test, not a
replacement for the unit tests in src/test.
"""

import argparse
import socket
import struct
import sys
import time

COLOR_RE = None


def strip_colors(text):
    global COLOR_RE
    if COLOR_RE is None:
        import re
        COLOR_RE = re.compile("\u00a7[0-9a-fk-orx]")
    return COLOR_RE.sub("", text)


class Rcon:
    def __init__(self, host, port, password, timeout=30.0):
        self.timeout = timeout
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.sendall(self._packet(1, 3, password))
        response = self._read_packet()
        if response is None or response[0] == -1:
            raise RuntimeError("RCON authentication failed")

    @staticmethod
    def _packet(request_id, packet_type, payload):
        body = struct.pack("<ii", request_id, packet_type) + payload.encode("utf8") + b"\x00\x00"
        return struct.pack("<i", len(body)) + body

    def _read_packet(self):
        raw = b""
        while len(raw) < 4:
            chunk = self.sock.recv(4 - len(raw))
            if not chunk:
                return None
            raw += chunk
        length = struct.unpack("<i", raw)[0]
        data = b""
        while len(data) < length:
            chunk = self.sock.recv(length - len(data))
            if not chunk:
                return None
            data += chunk
        request_id, packet_type = struct.unpack("<ii", data[:8])
        return request_id, packet_type, data[8:-2].decode("utf8", "replace")

    def command(self, command):
        self.sock.sendall(self._packet(2, 2, command))
        first = self._read_packet()
        if first is None:
            return ""
        parts = [first[2]]
        # Long responses are split over several packets.
        self.sock.settimeout(0.25)
        try:
            while True:
                extra = self._read_packet()
                if extra is None:
                    break
                parts.append(extra[2])
        except (socket.timeout, TimeoutError):
            pass
        finally:
            self.sock.settimeout(self.timeout)
        return strip_colors("".join(parts)).replace("\n", " ")

    def close(self):
        self.sock.close()


class Checker:
    def __init__(self, rcon):
        self.rcon = rcon
        self.failures = []

    def check(self, name, condition, detail=""):
        status = "PASS" if condition else "FAIL"
        print(f"[{status}] {name}" + (f" ({detail})" if detail else ""))
        if not condition:
            self.failures.append(name)

    def run(self, command):
        return self.rcon.command(command)

    def count_entities(self, selector):
        out = self.run(f"execute if entity {selector}")
        if "Test passed" not in out:
            return 0
        try:
            return int(out.rsplit("Count:", 1)[1].strip())
        except (IndexError, ValueError):
            return 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="change-me")
    args = parser.parse_args()

    rcon = Rcon(args.host, args.port, args.password)
    test = Checker(rcon)
    region = "MobBySmokeTest"

    try:
        # ---------------------------------------------------------------- commands and validation
        test.check("help output", "MobBy commands" in test.run("mobby help"))
        test.run(f"mobby remove {region} confirm")

        out = test.run(f"mobby active 500 -60 500 507 -56 507 {region}")
        test.check("region creation", "Created and activated" in out, out)
        base = test.run(f"mobby info {region.lower()}")
        test.check("info is case insensitive", region in base)
        test.check("volume reported", "Volume: 320 blocks" in base, base[:160])

        test.check("volume limit", "exceeds the limit" in
                   test.run("mobby active 0 -60 0 40 -20 40 TooBig"))
        test.check("duplicate name", "already exists" in
                   test.run(f"mobby active 500 -60 500 507 -56 507 mobbysmoketest"))
        test.check("overlap", "overlaps" in
                   test.run("mobby active 505 -60 500 520 -56 507 Overlapping"))
        test.check("invalid coordinate", "not a valid whole number" in
                   test.run(f"mobby active nope -60 500 507 -56 507 {region}"))
        test.check("invalid name", "Invalid region name" in
                   test.run("mobby active 500 -60 500 507 -56 507 Bad!"))

        # ---------------------------------------------------------------- build the test farm
        test.run("time set day")
        test.run("setblock 501 -60 500 minecraft:furnace")
        test.run("item replace block 501 -60 500 container.2 with minecraft:air")
        test.run("item replace block 501 -60 500 container.0 with minecraft:raw_iron 3")
        test.run("item replace block 501 -60 500 container.1 with minecraft:coal 1")
        test.run("fill 502 -61 502 505 -61 505 minecraft:farmland[moisture=7] replace")
        test.run("fill 502 -60 502 505 -60 505 minecraft:wheat[age=0] replace")
        test.run("setblock 505 -60 507 minecraft:piston[facing=east]")
        test.run("setblock 504 -60 507 minecraft:air replace")
        test.run("setblock 504 -60 507 minecraft:redstone_block")
        test.run(f"mobby stop {region}")
        test.run(f"mobby start {region}")
        info = test.run(f"mobby info {region}")
        test.check("block index finds farm", "1 furnace(s)" in info and "crop(s)" in info, info[-120:])

        time.sleep(6)
        test.check("redstone piston fires unattended",
                   "Test passed" in test.run("execute if block 506 -60 507 minecraft:piston_head"))
        test.check("furnace ticks unattended",
                   "raw_iron" in test.run("data get block 501 -60 500 Items"))

        # ---------------------------------------------------------------- simulation results
        time.sleep(40)
        items = test.run("data get block 501 -60 500 Items")
        test.check("furnace produced exactly three ingots",
                   'count: 3}' in items and 'iron_ingot' in items, items[:200])

        region_mobs = "@e[type=minecraft:zombie,x=500,y=-60,z=500,dx=7,dy=4,dz=7]"
        single_mob = "@e[type=minecraft:zombie,x=500,y=-60,z=500,dx=7,dy=4,dz=7,limit=1]"
        zombies = test.count_entities(region_mobs)
        test.check("mobs spawned without players", zombies > 0, f"{zombies} zombies in region")
        if zombies:
            tags = test.run(f"data get entity {single_mob} BukkitValues")
            test.check("mobs carry MobBy persistent data", "mobby:managed" in tags, tags[:160])

        crops = []
        for x in range(502, 506):
            for z in range(502, 506):
                for age in range(1, 8):
                    if "Test passed" in test.run(f"execute if block {x} -60 {z} minecraft:wheat[age={age}]"):
                        crops.append((x, z, age))
                        break
        test.check("crops grow unattended", bool(crops), f"{len(crops)} sampled crops above age 0")

        # ---------------------------------------------------------------- tickets and lifecycle
        test.run(f"mobby stop {region}")
        stopped = test.run(f"mobby info {region}")
        test.check("chunk tickets released on stop", "0 loaded with MobBy chunk tickets" in stopped)
        test.run(f"mobby start {region}")
        resumed = test.run(f"mobby info {region}")
        test.check("chunk tickets restored on start", "loaded with MobBy chunk tickets" in resumed)

        test.check("reload is safe", "Configuration reloaded" in test.run("mobby reload"))
        test.check("remove asks for confirmation", "confirm" in test.run(f"mobby remove {region}"))
        test.check("remove confirm deletes", "removed permanently" in
                   test.run(f"mobby remove {region} confirm"))
        test.check("region is gone", "Unknown region" in test.run(f"mobby info {region}"))
    finally:
        rcon.close()

    print()
    if test.failures:
        print(f"{len(test.failures)} check(s) failed: {', '.join(test.failures)}")
        return 1
    print("All MobBy integration checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
