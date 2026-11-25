"""Measure real gold collection from an unattended, one-cell vanilla cramming farm on build 256."""
import argparse
import json
import re
from pathlib import Path
import runpy
import shutil
import tempfile
import time

h = runpy.run_path(str(Path(__file__).with_name("paper-dev-integration.py")))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--paper-jar", required=True, type=Path)
    p.add_argument("--plugin-jar", default=Path("build/libs/MobBy-1.1.0.jar"), type=Path)
    p.add_argument("--java", required=True)
    p.add_argument("--evidence", required=True, type=Path)
    args = p.parse_args()
    if h["digest"](args.paper_jar) != h["PAPER_SHA256"]:
        p.error("Expected official Paper 26.3 build 256")
    args.evidence.mkdir(parents=True, exist_ok=False)
    report = h["Report"](args.evidence / "checks.jsonl")
    with tempfile.TemporaryDirectory(prefix="mobby-gold-") as temp:
        work = Path(temp)
        h["write_server"](work, args.plugin_jar, 25903, 25978)
        config = work / "plugins/MobBy/config.yml"
        # The game's default cramming threshold is 24. Provide sufficient region and species capacity.
        config.write_text(config.read_text().replace("max-mobs-per-region: 20", "max-mobs-per-region: 36")
                          .replace("ZOMBIFIED_PIGLIN: 12", "ZOMBIFIED_PIGLIN: 36"))
        shutil.copyfile(args.paper_jar, work / "paper.jar")
        try:
            proc, log = h["boot"](work, args.java, "1400M", 180)
            rcon = h["connect_owned"](proc, 25978, 20)
            try:
                report.check("zero players", "0 of a max" in rcon.cmd("list"))
                reply = rcon.cmd("mobby active 100 154 100 106 165 106 GoldFarm world_nether")
                report.check("farm active", "Created and activated" in reply, reply)
                prefix = "execute in minecraft:the_nether run "
                for cmd in ["fill 101 158 101 103 163 103 glass hollow",
                            "setblock 102 155 102 chest", "setblock 102 156 102 hopper",
                            "setblock 102 157 102 rail", "summon hopper_minecart 102.5 157.1 102.5",
                            "setblock 102 158 102 magma_block",
                            "fill 102 159 102 102 162 102 air"]:
                    rcon.cmd(prefix + cmd)
                rcon.cmd("mobby stop GoldFarm")
                rcon.cmd("mobby start GoldFarm")
                initial = rcon.cmd(prefix + "data get block 102 155 102 Items")
                report.check("collection chest starts empty", "[]" in initial, initial)
                started = time.monotonic()
                peak = 0
                while time.monotonic() - started < 180:
                    answer = rcon.cmd(prefix + "execute if entity @e[type=zombified_piglin,tag=mobby,x=102,y=159,z=102,dx=0,dy=3,dz=0]")
                    if "Count:" in answer:
                        peak = max(peak, int(answer.split("Count:")[-1].strip()))
                    items = rcon.cmd(prefix + 'data get block 102 155 102 Items[{id:"minecraft:gold_nugget"}].count')
                    nuggets = re.search(r"following block data: (\d+)\b", items)
                    if nuggets and int(nuggets[1]) > 0 and peak >= 24:
                        break
                    time.sleep(2)
                report.check("managed piglins reach cramming density", peak >= 24, f"peak sampled {peak}")
                report.check("farm delivers gold nuggets to chest with no players",
                             bool(nuggets and int(nuggets[1]) > 0) and "0 of a max" in rcon.cmd("list"),
                             f"{time.monotonic() - started:.1f}s, gold_nugget count query: {items}")
            finally:
                console = h["shutdown"](proc, rcon, log)
            report.check("farm shutdown clean", "MobBy disabled." in console)
            h["check_logs"]([log], report)
        finally:
            for log in work.glob("console-*.log"):
                shutil.copyfile(log, args.evidence / log.name)
            (args.evidence / "results.json").write_text(json.dumps(dict(plugin_sha256=h["digest"](args.plugin_jar),
                     paper_sha256=h["PAPER_SHA256"], checks=report.rows), indent=2))
    return int(bool(report.failures))


if __name__ == "__main__":
    raise SystemExit(main())
