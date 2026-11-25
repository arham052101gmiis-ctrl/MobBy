"""Single-process, continuous 180-minute soak of the immutable, validated dev1 artifact."""
import argparse
from datetime import datetime, timezone
import fcntl
import gzip
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import traceback

h = runpy.run_path(str(Path(__file__).with_name("paper-dev-integration.py")))
CANDIDATE = "413a57506f8328fe131d08bb514f0593cc388238234be4b6f71f40b1c412bb14"
PORT = 25985
REGIONS = {"GoldFarm": "100 154 100 106 165 106 GoldFarm world_nether",
           "Farm": "498 198 498 507 204 507 Farm", "Pen": "600 199 600 608 204 608 Pen",
           "DarkRoom": "100 200 100 108 206 108 DarkRoom"}


def utc():
    return datetime.now(timezone.utc).isoformat()


def persist(path, data):
    tmp = path.with_suffix(".new")
    with tmp.open("w") as out:
        json.dump(data, out, indent=2)
        out.flush()
        os.fsync(out.fileno())
    tmp.replace(path)


def append(path, data):
    with path.open("a") as out:
        out.write(json.dumps(data) + "\n")
        out.flush()
        os.fsync(out.fileno())


def proc_stats(pid):
    path = Path("/proc", str(pid))
    fields = (path / "stat").read_text().rsplit(")", 1)[1].split()
    rss = re.search(r"^VmRSS:\s+(\d+)", (path / "status").read_text(), re.M)
    return {"pid": pid, "start_ticks": int(fields[19]), "rss_kib": int(rss[1]),
            "cpu_seconds": (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")}


def boot(work, java, log, meta, ev):
    with log.open("xb") as console:
        proc = subprocess.Popen([java, "-Xms512M", "-Xmx1400M", "-XX:+UseG1GC", "-jar",
                                 "paper.jar", "--nogui", "--nojline", "--noconsole"],
                                cwd=work, stdin=subprocess.DEVNULL, stdout=console, stderr=subprocess.STDOUT)
    meta["server"] = proc_stats(proc.pid)
    persist(ev / "identity.json", meta)
    try:
        for _ in range(220):
            if proc.poll() is not None:
                raise RuntimeError("Paper startup exit " + str(proc.returncode))
            if "Done (" in log.read_text(errors="replace"):
                return proc, h["connect"](PORT, 20)
            time.sleep(1)
        raise RuntimeError("Paper startup timeout")
    except BaseException:
        if proc.poll() is None:
            proc.terminate()
            proc.wait(timeout=120)
        raise


def probe(rcon, mode="sample"):
    answer = rcon.cmd("mobbysoak " + mode)
    try:
        return json.loads(answer)
    except ValueError:
        raise RuntimeError(f"Soak probe {mode}: {answer[:256]}")


def fixtures(rcon):
    for args in REGIONS.values():
        reply = rcon.cmd("mobby active " + args)
        if "Created and activated" not in reply:
            raise RuntimeError(reply)
    for cmd in ["fill 101 158 101 103 163 103 glass hollow", "setblock 102 155 102 chest",
                "setblock 102 156 102 hopper", "setblock 102 157 102 rail",
                "summon hopper_minecart 102.5 157.1 102.5", "setblock 102 158 102 magma_block",
                "fill 102 159 102 102 162 102 air"]:
        rcon.cmd("execute in minecraft:the_nether run " + cmd)
    for cmd in ["time set noon", "fill 100 200 100 108 206 108 stone hollow",
                "fill 600 199 600 608 204 608 glass hollow", "fill 601 199 601 607 199 607 grass_block",
                "fill 600 204 600 608 204 608 glowstone", "summon cow 603 200 603", "summon cow 604 200 603",
                "setblock 501 198 500 chest", "setblock 501 199 500 hopper", "setblock 501 200 500 furnace",
                "setblock 501 199 504 water", "fill 502 199 502 505 199 505 farmland[moisture=7]",
                "fill 502 200 502 505 200 505 wheat[age=0]", "fill 502 203 502 505 203 505 glowstone",
                "setblock 505 200 507 piston[facing=east]", "setblock 504 200 507 redstone_block"]:
        rcon.cmd(cmd)
    for region in REGIONS:
        rcon.cmd("mobby stop " + region)
        rcon.cmd("mobby start " + region)
    probe(rcon, "service")


def main(args):
    ev = args.evidence.resolve()
    ev.parent.mkdir(parents=True, exist_ok=True)
    # Never attach a fresh JVM to an existing run's measurements.
    with (ev.parent / "soak.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        ev.mkdir(exist_ok=False)
        run(args, ev)


def run(args, ev):
    java = args.java.resolve()
    version = subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True)
    hashes = {name: h["digest"](path) for name, path in
              [("plugin_sha256", args.plugin_jar), ("paper_sha256", args.paper_jar), ("probe_sha256", args.probe_jar)]}
    if hashes["plugin_sha256"] != CANDIDATE or hashes["paper_sha256"] != h["PAPER_SHA256"] or not re.search(r'version "25[."]', version):
        raise RuntimeError("Artifact or Java preflight failed")
    for port in [25984, PORT]:
        with socket.socket() as s:
            s.bind(("127.0.0.1", port))
    work = Path(tempfile.mkdtemp(prefix="mobby-180-"))
    meta = dict(**hashes, created_utc=utc(), boot_id=Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
                java=version, monitor=proc_stats(os.getpid()), work=str(work), evidence=str(ev))
    persist(ev / "identity.json", meta)
    h["write_server"](work, args.plugin_jar, 25984, PORT)
    cfg = work / "plugins/MobBy/config.yml"
    cfg.write_text(cfg.read_text().replace("max-mobs-per-region: 20", "max-mobs-per-region: 36")
                   .replace("ZOMBIFIED_PIGLIN: 12", "ZOMBIFIED_PIGLIN: 36"))
    shutil.copy2(cfg, ev / "fixture-config.yml")
    shutil.copy2(args.probe_jar, work / "plugins/MobBySoakProbe.jar")
    shutil.copy2(args.paper_jar, work / "paper.jar")
    if h["digest"](work / "plugins" / args.plugin_jar.name) != CANDIDATE:
        raise RuntimeError("Staged plugin mismatch")
    proc = rcon = None
    result = {"state": "starting", "samples": 0, "elapsed_seconds": 0, "gate_failures": []}
    persist(ev / "status.json", result)
    log = ev / "soak-console.log"

    def check(name, ok, detail=None):
        append(ev / "checks.jsonl", dict(time=utc(), check=name, passed=bool(ok), detail=detail))
        if not ok:
            result["gate_failures"].append(name)

    def gc_baseline():
        subprocess.run([java.with_name("jcmd"), str(proc.pid), "GC.run"], check=True, capture_output=True, timeout=30)
        return probe(rcon)["heap_used"]

    def shutdown():
        nonlocal proc, rcon
        console = h["shutdown"](proc, rcon, log)
        proc = rcon = None
        check("Paper exited cleanly and MobBy disabled " + log.name, "MobBy disabled." in console)
        regions_file = work / "plugins/MobBy/regions.yml"
        if regions_file.exists():
            shutil.copyfile(regions_file, ev / (log.stem + "-regions.yml"))

    try:
        proc, rcon = boot(work, java, log, meta, ev)
        fixtures(rcon)
        deadline = time.monotonic() + 180
        while True:
            s = probe(rcon)
            if s["nuggets"] and s["ingots"] and s["grown"] and s["breed_events"] and s["piston"]:
                break
            if time.monotonic() > deadline:
                raise RuntimeError("Fixtures failed warm-up: " + json.dumps(s))
            time.sleep(3)
        initial = probe(rcon, "service")
        check("preflight live output, breeding and piston", True, initial)
        result["retained_heap_before_bytes"] = gc_baseline()
        old = proc_stats(proc.pid)
        start = previous = time.monotonic()
        result.update(state="running", start_utc=utc(), monotonic_start=start, server_start_ticks=old["start_ticks"])
        persist(ev / "status.json", result)
        totals = dict(nuggets=0, ingots=0, calves_removed=0)
        log_offset = 0
        for i in range(361):
            target = start + i * 30
            time.sleep(max(0, target - time.monotonic()))
            stamp = time.monotonic()
            if proc.poll() is not None:
                raise RuntimeError("Original Paper process exited")
            stat = proc_stats(proc.pid)
            if stat["start_ticks"] != result["server_start_ticks"]:
                raise RuntimeError("Server identity changed")
            service = i > 0 and i % 10 == 0
            s = probe(rcon, "service" if service else "sample")
            infos = {region: rcon.cmd("mobby info " + region) for region in REGIONS}
            tps, mspt = rcon.cmd("tps"), rcon.cmd("mspt")
            with log.open("rb") as stream:
                stream.seek(log_offset)
                lines = stream.read().decode(errors="replace").splitlines()
                log_offset = stream.tell()
            issues = [line[:800] for line in lines if re.search(r'WARN\]|ERROR\]|Exception|Caused by:', line)]
            row = dict(index=i, utc=utc(), elapsed_seconds=stamp-start, lateness_s=stamp-target,
                       cpu_pct_one_core=(100*(stat["cpu_seconds"]-old["cpu_seconds"])/(stamp-previous) if i else None),
                       rss_kib=stat["rss_kib"], tps=tps, mspt=mspt, fixture=s, regions=infos,
                       service=service, log_warnings_errors=issues)
            old, previous = stat, stamp
            violations = []
            if s["players"] or s["tickets"] != initial["tickets"] or s["crops"] != 16:
                violations.append("players, ticket set or crop population")
            if s["piston"] != s["powered"] or not all("Status: ACTIVE" in v for v in infos.values()):
                violations.append("piston state or region activity")
            if not re.search(r'\d+\.\d+', tps) or not re.search(r'\d+\.\d+', mspt):
                violations.append("telemetry parse")
            if service:
                for key in totals:
                    totals[key] += s[key]
                if not (s["nuggets"] and s["ingots"] and s["grown"]):
                    violations.append("five-minute output or growth gap")
            row["violations"] = violations
            append(ev / "samples.jsonl", row)
            result.update(samples=i+1, elapsed_seconds=stamp-start, last_sample_utc=row["utc"],
                          collected=dict(totals), observations_with_violations=result.get("observations_with_violations",0)+bool(violations))
            persist(ev / "status.json", result)
            if service:
                append(ev / "checkpoints.jsonl", dict(**result, checkpoint=i//10,
                       server_pid=proc.pid, monitor_pid=os.getpid(), server_tick=s["server_tick"],
                       console_bytes=log_offset, tickets=s["tickets"]))
            if i < 360:
                power = "air" if s["powered"] else "redstone_block"
                rcon.cmd("setblock 504 200 507 " + power)
        result["interval_completed"] = True
        result["end_utc"] = utc()
        result["retained_heap_after_bytes"] = gc_baseline()
        check("continuous 180 minutes", result["elapsed_seconds"] >= 10800 and result["samples"] == 361)
        check("all periodic workload assertions", not result["observations_with_violations"], result["observations_with_violations"])
        check("breeding continues", s["breed_events"] > initial["breed_events"] and totals["calves_removed"] > 0)
        check("piglin turnover", s["piglin_spawn_events"] > initial["piglin_spawn_events"] and s["managed_piglin_deaths"] > initial["managed_piglin_deaths"])
        shutdown()
        stored=(ev / "soak-console-regions.yml").read_text()
        check("four ACTIVE regions persisted", stored.count("ACTIVE")==4)
        log = ev / "restart-console.log"
        proc, rcon = boot(work, java, log, meta, ev)
        restored={k:rcon.cmd("mobby info "+k) for k in REGIONS}
        check("restart regions active with original tickets", all("Status: ACTIVE" in v for v in restored.values())
              and probe(rcon,"tickets")["tickets"]==initial["tickets"],restored)
        for region in REGIONS:
            rcon.cmd("mobby remove " + region)
            rcon.cmd("mobby remove " + region + " confirm")
        final = probe(rcon,"tickets")
        check("all plugin tickets released and managed entities cleaned", not any(final["tickets"].values()) and final["loaded_managed"] == 0, final)
        shutdown()
        check("candidate byte identity", h["digest"](work/"plugins"/args.plugin_jar.name)==CANDIDATE)
        result["state"]="completed-with-findings" if result["gate_failures"] else "passed"
    except BaseException as error:
        result.update(state="interrupted" if not result.get("interval_completed") else "post-test-error", error=repr(error), traceback=traceback.format_exc())
    finally:
        if proc is not None and proc.poll() is None and rcon is not None:
            try:
                shutdown()
            except Exception as error:
                result["shutdown_error"]=repr(error)
        persist(ev/"status.json",result)
        for f in sorted((work/"logs").glob("*.log*")):
            shutil.copyfile(f,ev/("paper-"+f.name))
        if result["state"] in ("passed","completed-with-findings") and not result.get("shutdown_error"):
            shutil.rmtree(work)
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("java","paper-jar","plugin-jar","probe-jar","evidence"):
        parser.add_argument("--"+name, type=Path, required=True)
    main(parser.parse_args())
