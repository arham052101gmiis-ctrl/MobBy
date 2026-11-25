"""Inspect, follow or package an existing soak attempt without starting a Minecraft server."""
import argparse
from collections import Counter
from datetime import datetime, timezone, timedelta
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import statistics
import time
from zipfile import ZipFile, ZIP_DEFLATED

EXPECTED = "413a57506f8328fe131d08bb514f0593cc388238234be4b6f71f40b1c412bb14"
ROOT = Path(__file__).resolve().parents[1]
DUBAI = timezone(timedelta(hours=4))
TERMINAL = {"passed","interrupted","completed-with-findings","post-test-error"}


def load(path):
    return json.loads(path.read_text())


def records(path):
    if not path.exists(): return []
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def alive(identity, boot_id):
    if not identity or Path("/proc/sys/kernel/random/boot_id").read_text().strip() != boot_id:
        return False
    try:
        fields=(Path("/proc")/str(identity["pid"])/"stat").read_text().rsplit(")",1)[1].split()
        return fields[0] != "Z" and int(fields[19])==identity["start_ticks"]
    except (FileNotFoundError, ProcessLookupError):
        return False


def inspect(ev):
    state=load(ev/"status.json")
    ids=load(ev/"identity.json")
    return dict(state=state["state"], last_sample=state.get("last_sample_utc"),samples=state["samples"],
                elapsed_seconds=state["elapsed_seconds"],monitor_alive=alive(ids.get("monitor"),ids["boot_id"]),
                server_alive=alive(ids.get("server"),ids["boot_id"]))


def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f,"sha256").hexdigest()


def stats(values):
    values=sorted(values)
    return dict(min=values[0],median=statistics.median(values),p95=values[math.ceil(len(values)*.95)-1],max=values[-1])


def local_time(iso):
    return datetime.fromisoformat(iso).astimezone(DUBAI).isoformat(timespec="seconds") if iso else "unavailable"


def publish(ev, out, jar, probe):
    out.mkdir(parents=True,exist_ok=True)
    state=load(ev/"status.json")
    ids=load(ev/"identity.json")
    rows=records(ev/"samples.jsonl")
    checks=records(ev/"checks.jsonl")
    cps=records(ev/"checkpoints.jsonl")
    if digest(jar)!=EXPECTED or ids["plugin_sha256"]!=EXPECTED or digest(probe)!=ids["probe_sha256"]:
        raise ValueError("Artifact mismatch")
    outcome=inspect(ev)
    if outcome["monitor_alive"] and state["state"] not in TERMINAL:
        raise RuntimeError("Monitor is active")
    elapsed=rows[-1]["elapsed_seconds"] if rows else 0
    complete=bool(state.get("interval_completed") and elapsed>=10800 and [r["index"] for r in rows]==list(range(361)))
    findings=[]
    if not complete: findings.append("The evidence ends before a complete, uninterrupted 180-minute record.")
    findings.extend(state.get("gate_failures",[]))
    if state.get("error"): findings.append(state["error"])
    if state.get("shutdown_error"): findings.append(state["shutdown_error"])
    if outcome["server_alive"]: findings.append("The recorded Paper process is still running. The available samples end at the last timestamp.")
    metrics={}
    activities={}
    services=[r["fixture"] for r in rows if r["service"]]
    if rows:
        for key,values in {
            "1-minute TPS":[float(re.search(r":\s*\*?(\d+(?:\.\d+)?)",r["tps"])[1]) for r in rows],
            "5-second mean MSPT":[float(re.search(r"(\d+\.\d+)/",r["mspt"])[1]) for r in rows],
            "CPU, % of one logical core":[r["cpu_pct_one_core"] for r in rows if r["cpu_pct_one_core"] is not None],
            "Used heap, MiB":[r["fixture"]["heap_used"]/2**20 for r in rows],
            "Process RSS, MiB":[r["rss_kib"]/1024 for r in rows],
            "Sample start delay, seconds":[r["lateness_s"] for r in rows],
        }.items():
            if values: metrics[key]=stats(values)
        base=rows[0]["fixture"]  # Exclude event increments between warm-up and timed sampling.
        last=rows[-1]["fixture"]
        activities=dict(nuggets_collected=sum(v["nuggets"] for v in services),
                        iron_ingots_collected=sum(v["ingots"] for v in services),
                        periods_with_crop_growth=sum(v["grown"]>0 for v in services),
                        five_minute_harvests=len(services),
                        calves_removed=sum(v["calves_removed"] for v in services),
                        observed_births=last["breed_events"]-base.get("breed_events",0),
                        piglin_spawn_events=last["piglin_spawn_events"]-base.get("piglin_spawn_events",0),
                        managed_piglin_deaths=last["managed_piglin_deaths"]-base.get("managed_piglin_deaths",0),
                        observed_piston_transitions=sum(a["fixture"]["piston"]!=b["fixture"]["piston"] for a,b in zip(rows,rows[1:])),
                        samples_without_players=sum(r["fixture"]["players"]==0 for r in rows),
                        piglin_population_range=stats([r["fixture"]["managed_piglins"] for r in rows]),
                        loaded_entities_range=stats([sum(r["fixture"]["loaded_entities"].values()) for r in rows]),
                        rss_first_last_MiB=[rows[0]["rss_kib"]/1024,rows[-1]["rss_kib"]/1024],
                        distinct_ticket_sets=len({json.dumps(r["fixture"]["tickets"],sort_keys=True) for r in rows}),
                        final_gc_counts=last["gc_counts"])
        if activities["observed_piston_transitions"]!=len(rows)-1: findings.append("At least one commanded piston transition was missed.")
        if any(r["lateness_s"]>=30 for r in rows): findings.append("At least one sample exceeded its scheduled time by a full interval.")
    alerts=Counter()
    for log in sorted(ev.glob("*console.log")):
        for line in log.read_text(errors="replace").splitlines():
            if re.search(r"WARN\]|ERROR\]|Exception|Caused by:",line):
                line=re.sub(r"^.*?(?:WARN|ERROR)\]:\s*","",line)
                alerts[line.strip()[:400]]+=1
    retained=[state.get("retained_heap_before_bytes"),state.get("retained_heap_after_bytes")]
    memo="A matched full-GC endpoint pair is unavailable."
    if all(x is not None for x in retained):
        delta=(retained[1]-retained[0])/2**20
        memo=f"Outside the timed interval, full-GC used heap measured {retained[0]/2**20:.1f} MiB before and {retained[1]/2**20:.1f} MiB after ({delta:+.1f} MiB). "
        if rows:
            windows=[min(r["fixture"]["heap_used"] for r in rows[start:start+60])/2**20 for start in range(0,len(rows)-1,60)]
            memo+="Successive roughly 30-minute minimum heap observations in MiB were "+", ".join(f"{v:.1f}" for v in windows)+". "
        memo+="These measurements include JVM and Paper allocation, chunk caches and the test probe. Attribution of retained growth requires additional evidence."
    stored={}
    snapshot=ev/"soak-console-regions.yml"
    if snapshot.exists():
        for name,ms in re.findall(r"^  (\w+):\n(?:(?!^  \w+:).)*?^    accumulated-uptime-ms: (\d+)",snapshot.read_text(),re.M|re.S):
            stored[name]=int(ms)
    summary=dict(complete_uninterrupted_interval=complete,state=state,identity=ids,metrics=metrics,activity=activities,
                 checkpoint_count=len(cps),persisted_uptime_ms=stored,alert_signatures=alerts,findings=findings)
    (ev/"summary.json").write_text(json.dumps(summary,indent=2)+"\n")
    label=("Completed with findings" if findings else "Passed") if complete else "Interrupted or incomplete"
    heading=f"{label}. Recorded {elapsed/60:.2f} minutes in this one server lifetime, with {len(rows)} samples and {len(cps)} five-minute checkpoints."
    lines=["# MobBy 1.2.0-dev1 soak report","",heading,"",
           f"Run ID `{ev.name}`. Sampling began {local_time(state.get('start_utc'))} and the last sample was {local_time(state.get('last_sample_utc'))} (Dubai). The server ran Paper 26.3 build 256 on Java 25 with a 1,400 MiB heap ceiling and loopback-only access.","",
           f"Candidate SHA-256 `{EXPECTED}`. Previous validation recorded 65 JVM tests, 196 Paper checks and seven gold output checks. This soak used the same candidate bytes. Server and monitor identities, artifact hashes, exact timestamps and raw command replies are in the evidence archive.","",
           "## Performance","","| Measurement | Minimum | Median | 95th percentile | Maximum |","|---|---:|---:|---:|---:|"]
    for k,v in metrics.items(): lines.append("| "+k+" | "+" | ".join(f"{v[x]:.2f}" for x in ("min","median","p95","max"))+" |")
    lines+=["","CPU is the Java process CPU time delta over the preceding sampling interval. 100% represents one logical core. MSPT summarizes the sampled five-second tick averages. JVM heap and Linux resident memory are separate measurements.","","## Memory retention","",memo,"","## Farms and regions",""]
    if rows:
        a=activities
        lines += [f"- Zero players in {a['samples_without_players']}/{len(rows)} observations. The Nether roof fixture recorded {a['piglin_spawn_events']} piglin spawns, {a['managed_piglin_deaths']} managed piglin deaths and {a['nuggets_collected']} collected gold nuggets.",
                  f"- The furnace and hopper delivered {a['iron_ingots_collected']} iron ingots. Wheat advanced in {a['periods_with_crop_growth']}/{len(services)} harvest periods. A piston changed state {a['observed_piston_transitions']} times between samples.",
                  f"- The pen recorded {a['observed_births']} vanilla breeding events. Scheduled fixture maintenance removed {a['calves_removed']} calves. Total loaded entities ranged from {a['loaded_entities_range']['min']} to {a['loaded_entities_range']['max']} across the loaded worlds.",
                  f"- Paper ticket ownership had {a['distinct_ticket_sets']} distinct configuration(s) across all samples, ending with {sum(map(len,last['tickets'].values()))} tickets. Region status and uptime were recorded every 30 seconds. Stored uptimes in milliseconds after the soak shutdown were `{json.dumps(stored)}`.",""]
    lines+=["The fixture replenished furnace input and fuel, cleared output chests, reset wheat age and collected calves every five minutes. The redstone input alternated every 30 seconds. Gold production used a magma-floor cramming cell with a 36-piglin cap and a hopper minecart. Farm output totals count the items present at collection. The four active regions used continuous empty-server ticking and the isolated server’s activation settings.","","## Shutdown, restart and cleanup",""]
    lines += [f"- {'PASS' if c['passed'] else 'FAIL'} — {c['check']}" for c in checks if not c['check'].startswith("preflight")]
    lines+=["","## Console review","","Distinct warning and exception signatures from both server lifetimes are counted below. Full logs are included.",""]
    lines += [f"- {n} × `{s}`" for s,n in alerts.most_common()] or ["No matching lines."]
    lines+=["","## Remaining findings",""]+(["- "+f for f in findings] or ["The recorded gates passed for this workload. Concurrent player load, other plugins, older Paper versions and longer memory trends remain outside this run."])
    body="\n".join(lines)+"\n"
    (out/"SOAK-REPORT.md").write_text(body)
    for name,description in {
        "DEVELOPMENT-STATUS":heading,
        "TEST-REPORT":heading+" See SOAK-REPORT.md for the detailed metrics, caveats and restart checks.",
        "CHANGELOG":"Added a separate soak probe, autonomous 30-second sampler, durable five-minute checkpoints and post-soak persistence and cleanup verification. Candidate bytes matched the validated hash.",
        "HANDOFF":f"Run {ev.name}. Consult SOAK-REPORT.md and evidence/soak/{ev.name}/. To inspect a surviving run use scripts/soak-results.py with its evidence directory. A new run always requires a fresh ID. Keep partial and complete runs separate.",
        "NEXT-STEPS":("Investigate the findings in SOAK-REPORT.md before additional release decisions." if findings else "Review the monitored workload and its coverage before broader release testing. Other Paper versions and mixed-plugin production loads need separate validation.")
    }.items():
        p=ROOT/(name+".md")
        if f"## 180-minute soak · {ev.name}" not in p.read_text():
            top,rest=p.read_text().split("\n",1)
            p.write_text(top+f"\n\n## 180-minute soak · {ev.name}\n\n"+description+"\n"+rest)
    shutil.copyfile(out/"SOAK-REPORT.md", ROOT/"SOAK-REPORT.md")
    shutil.copy2(jar,out/jar.name)
    source=out/"MobBy-1.2.0-dev1-soak-source.zip"
    with ZipFile(source.with_suffix(".tmp"),"w",ZIP_DEFLATED,compresslevel=7) as archive:
        for p in sorted(ROOT.rglob("*")):
            if p.is_file() and not set(p.relative_to(ROOT).parts)&{"__pycache__",".gradle","build"}:
                archive.write(p,Path(ROOT.name)/p.relative_to(ROOT))
        for p in sorted(ev.iterdir()):
            if p.is_file():
                archive.write(p,Path(ROOT.name)/"evidence/soak"/ev.name/p.name)
        archive.write(probe,Path(ROOT.name)/"evidence/soak"/"MobBySoakProbe.jar")
    source.with_suffix(".tmp").replace(source)
    with ZipFile(source) as z:
        if z.testzip(): raise RuntimeError("ZIP integrity failure")
    files=[out/jar.name,source,out/"SOAK-REPORT.md"]
    (out/"SHA256SUMS.txt").write_text("".join(f"{digest(p)}  {p.name}\n" for p in files))


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("evidence",type=Path);p.add_argument("--follow",action="store_true")
    p.add_argument("--output",type=Path);p.add_argument("--jar",type=Path);p.add_argument("--probe",type=Path)
    args=p.parse_args()
    while True:
        status=inspect(args.evidence)
        if not args.follow or not status["monitor_alive"]: break
        time.sleep(15)
    print(json.dumps(status),flush=True)
    if args.output:
        publish(args.evidence,args.output,args.jar,args.probe)
