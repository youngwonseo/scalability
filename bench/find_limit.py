#!/usr/bin/env python3
"""Find the max sustainable RPS of one workload (EXPERIMENT_PLAN.md 4.3).

Each step is a separate constant-rate k6 run, judged against the SLO:
  0. warmup: one unjudged run at the start rate
  1. coarse: double the rate until a step fails
  2. fine:   from 70% of the first failing rate, +5% per step until a step fails -> knee = last passing rate
  3. confirm: run the knee twice for --confirm-duration; if a run fails, step down 5% and retry.
              Latencies in the result are the worst of the confirm runs.

Results: results/<label>/<workload>/{steps.jsonl, result.json, k6 summaries}; one row appended to results/results.csv.

Local (docker compose):  python3 bench/find_limit.py --label local-s2 --workload R1 --users 10000 --posts 100000
Cloud (native k6):       K6_CMD=k6 K6_SCRIPT=k6/workload.js K6_RESULTS_DIR=results BASE_URL=http://<lb> python3 bench/find_limit.py ...
"""

import argparse
import csv
import json
import os
import shlex
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOST_RESULTS = ROOT / "results"

# How k6 is invoked. Defaults run it inside the compose network.
K6_CMD = shlex.split(os.environ.get("K6_CMD", "docker compose run --rm -T k6"))
K6_SCRIPT = os.environ.get("K6_SCRIPT", "/scripts/workload.js")
# results/ as seen by k6 (container path when running in docker)
K6_RESULTS_DIR = os.environ.get("K6_RESULTS_DIR", "/results")
PROMETHEUS_RW = os.environ.get("K6_PROMETHEUS_RW", "1") == "1"

SLO_P99_MS = 200
SLO_P95_MS = 100
SLO_ERROR_RATE = 0.01
# A step only counts if k6 actually delivered the target rate
MIN_DELIVERED_RATIO = 0.95

# Cloud: Prometheus with node_exporter targets labelled host=<name>. Each step then records per-host
# CPU, and a step where the load generator itself is busier than this is not trusted.
PROM_URL = os.environ.get("PROM_URL")
LOADGEN_HOST = os.environ.get("LOADGEN_HOST", "loadgen-1")
LOADGEN_CPU_LIMIT = 0.85


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--label", required=True, help="scenario + configuration, e.g. s2-app4")
    p.add_argument("--workload", required=True, choices=["R1", "R2", "W1", "W2", "M1", "M2", "M3"])
    p.add_argument("--users", type=int, default=1_000_000)
    p.add_argument("--posts", type=int, default=10_000_000)
    p.add_argument("--dist", choices=["uniform", "zipf"], help="default: zipf for W2, uniform otherwise")
    p.add_argument("--start", type=int, default=100, help="first coarse rate (use previous scenario's result)")
    p.add_argument("--max-rate", type=int, default=200_000)
    p.add_argument("--warmup-duration", default="60s", help="unjudged run at --start (JIT, buffer pool, connections)")
    p.add_argument("--coarse-duration", default="30s")
    p.add_argument("--fine-duration", default="60s")
    p.add_argument("--confirm-duration", default="5m")
    p.add_argument("--confirm-runs", type=int, default=2)
    p.add_argument("--pause", type=int, default=5, help="seconds between steps")
    p.add_argument("--raw-rate", type=int, default=0,
                   help="read-after-write probes per second alongside the load (S3 consistency check)")
    p.add_argument("--coarse-only", action="store_true",
                   help="stop after the coarse phase (connection pool sweep); max_rps = last passing coarse rate")
    return p.parse_args()


class Bench:
    def __init__(self, args):
        self.args = args
        self.dist = args.dist or ("zipf" if args.workload == "W2" else "uniform")
        self.out_dir = HOST_RESULTS / args.label / args.workload
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self.step_no = 0

    def run_step(self, phase, rate, duration):
        self.step_no += 1
        name = f"{self.step_no:02d}-{phase}-{rate}.json"
        env = {
            "WORKLOAD": self.args.workload,
            "RATE": rate,
            "DURATION": duration,
            "USERS": self.args.users,
            "POSTS": self.args.posts,
            "DIST": self.dist,
            "RAW_RATE": self.args.raw_rate,
            "SUMMARY_PATH": f"{K6_RESULTS_DIR}/{self.args.label}/{self.args.workload}/{name}",
        }
        if "BASE_URL" in os.environ:
            env["BASE_URL"] = os.environ["BASE_URL"]
        cmd = list(K6_CMD) + ["run", "--quiet", "--tag", f"testid={self.args.label}/{self.args.workload}"]
        if PROMETHEUS_RW:
            cmd += ["--out", "experimental-prometheus-rw"]
        for k, v in env.items():
            cmd += ["-e", f"{k}={v}"]
        cmd.append(K6_SCRIPT)

        print(f"[{phase}] {rate}/s for {duration} ...", flush=True)
        started = time.time()
        proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
        elapsed = time.time() - started
        # 99 = thresholds crossed; that is expected and judged below
        if proc.returncode not in (0, 99):
            sys.stderr.write(proc.stdout + proc.stderr)
            raise SystemExit(f"k6 failed with exit code {proc.returncode}")

        step = self.judge(phase, rate, duration, json.loads((self.out_dir / name).read_text()))
        step["host_cpu"] = host_cpu(elapsed)
        loadgen_cpu = step["host_cpu"].get(LOADGEN_HOST)
        if loadgen_cpu is not None and loadgen_cpu > LOADGEN_CPU_LIMIT:
            step["violations"].append("loadgen-cpu")
            step["pass"] = False
        with open(self.out_dir / "steps.jsonl", "a") as f:
            f.write(json.dumps(step) + "\n")
        verdict = "(not judged)" if phase == "warmup" else "PASS" if step["pass"] else "FAIL (" + ", ".join(step["violations"]) + ")"
        print(
            f"        actual={step['actual_rps']:.0f}/s p95={step['p95_ms']:.1f}ms p99={step['p99_ms']:.1f}ms "
            f"err={step['error_rate'] * 100:.2f}% read_p99={fmt(step['read_p99_ms'])} "
            f"write_p99={fmt(step['write_p99_ms'])}"
            + ("" if step["stale_read_rate"] is None else f" stale={step['stale_read_rate'] * 100:.2f}%")
            + (" cpu[" + " ".join(f"{h}={c * 100:.0f}%" for h, c in sorted(step["host_cpu"].items())) + "]"
               if step["host_cpu"] else "")
            + f" -> {verdict}",
            flush=True,
        )
        time.sleep(self.args.pause)
        return step

    @staticmethod
    def judge(phase, rate, duration, summary):
        m = summary["metrics"]

        def val(metric, stat):
            return m.get(metric, {}).get("values", {}).get(stat)

        def by_type(type_, stat):
            # sub-metrics exist even when no request of that type was sent
            if not val(f"http_reqs{{type:{type_}}}", "count"):
                return None
            return val(f"http_req_duration{{type:{type_}}}", stat)

        step = {
            "phase": phase,
            "target_rps": rate,
            "duration": duration,
            "actual_rps": val("http_reqs{scenario:load}", "rate") or 0.0,
            "p95_ms": val("http_req_duration{scenario:load}", "p(95)") or 0.0,
            "p99_ms": val("http_req_duration{scenario:load}", "p(99)") or 0.0,
            "error_rate": val("http_req_failed{scenario:load}", "rate") or 0.0,
            "read_p99_ms": by_type("read", "p(99)"),
            "write_p99_ms": by_type("write", "p(99)"),
            "read_rps": val("http_reqs{type:read}", "rate") or 0.0,
            "write_rps": val("http_reqs{type:write}", "rate") or 0.0,
            "dropped": val("dropped_iterations", "count") or 0,
            "stale_read_rate": val("stale_reads", "rate"),
        }
        violations = []
        if step["p99_ms"] >= SLO_P99_MS:
            violations.append("p99")
        if step["p95_ms"] >= SLO_P95_MS:
            violations.append("p95")
        if step["error_rate"] >= SLO_ERROR_RATE:
            violations.append("errors")
        if step["actual_rps"] < rate * MIN_DELIVERED_RATIO:
            violations.append("under-delivered")
        step["violations"] = violations
        step["pass"] = not violations
        return step

    def find(self):
        a = self.args
        # 0. warm up: a cold JVM alone can push p99 above the SLO
        self.run_step("warmup", a.start, a.warmup_duration)

        # 1. coarse
        rate, last_pass, fail_rate = a.start, None, None
        self.coarse_steps = []
        while rate <= a.max_rate:
            step = self.run_step("coarse", rate, a.coarse_duration)
            self.coarse_steps.append(step)
            if step["pass"]:
                last_pass, rate = rate, rate * 2
            else:
                fail_rate = rate
                break
        if fail_rate is None:
            raise SystemExit(f"No failure up to --max-rate {a.max_rate}; raise it")
        if last_pass is None:
            raise SystemExit(f"Failed already at --start {a.start}; lower it")
        if a.coarse_only:
            return last_pass, [s for s in self.coarse_steps if s["target_rps"] == last_pass]

        # 2. fine
        increment = max(1, round(fail_rate * 0.05))
        rate = max(last_pass, round(fail_rate * 0.7))
        knee = None
        # keep going past fail_rate: a single coarse failure can be transient
        while rate <= a.max_rate:
            step = self.run_step("fine", rate, a.fine_duration)
            if not step["pass"]:
                self.stopped_by = step
                break
            knee = rate
            rate += increment
        knee = knee or last_pass

        # 3. confirm
        for _ in range(3):
            runs = [self.run_step("confirm", knee, a.confirm_duration) for _ in range(a.confirm_runs)]
            if all(r["pass"] for r in runs):
                return knee, runs
            knee = max(1, knee - increment)
        raise SystemExit("Could not confirm a stable rate; results are too noisy")

    def save(self, knee, runs):
        # report the worst confirm run (conservative)
        def worst(key):
            values = [r[key] for r in runs if r[key] is not None]
            return max(values) if values else None

        result = {
            "label": self.args.label,
            "workload": self.args.workload,
            "dist": self.dist,
            "mode": "coarse-only" if self.args.coarse_only else "full",
            "max_rps": knee,
            "p95_ms": worst("p95_ms"),
            "p99_ms": worst("p99_ms"),
            "read_p99_ms": worst("read_p99_ms"),
            "write_p99_ms": worst("write_p99_ms"),
            "error_rate": worst("error_rate"),
            "stale_read_rate": worst("stale_read_rate"),
            # what ended the search: SLO violations of the first failing fine step
            "stopped_by": ",".join(getattr(self, "stopped_by", {}).get("violations", [])),
            "limited_by": "loadgen" if "loadgen-cpu" in getattr(self, "stopped_by", {}).get("violations", [])
            else "system",
            # per-host CPU during the last confirm run: the bottleneck is usually the busiest host
            "host_cpu": runs[-1].get("host_cpu", {}),
            "finished_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        }
        (self.out_dir / "result.json").write_text(json.dumps(result, indent=2))
        csv_path = HOST_RESULTS / "results.csv"
        new = not csv_path.exists()
        with open(csv_path, "a", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(result))
            if new:
                w.writeheader()
            w.writerow({**result, "host_cpu": json.dumps(result["host_cpu"], sort_keys=True)})
        print(f"\n==> {self.args.label} {self.args.workload}: max sustainable {knee}/s "
              f"(p99 {fmt(result['p99_ms'])}, read p99 {fmt(result['read_p99_ms'])}, "
              f"write p99 {fmt(result['write_p99_ms'])}, limited by {result['limited_by']})")


def host_cpu(window_seconds):
    """Average CPU (0..1) per host over the last window, from node_exporter. Empty without PROM_URL."""
    if not PROM_URL:
        return {}
    window = max(15, int(window_seconds))
    query = f'1 - avg by (host) (rate(node_cpu_seconds_total{{mode="idle"}}[{window}s]))'
    try:
        with urllib.request.urlopen(f"{PROM_URL}/api/v1/query?" + urllib.parse.urlencode({"query": query}),
                                    timeout=10) as response:
            data = json.load(response)
        return {r["metric"].get("host", "?"): round(float(r["value"][1]), 3) for r in data["data"]["result"]}
    except Exception as e:  # monitoring must never break a measurement
        print(f"        (host cpu unavailable: {e})", flush=True)
        return {}


def fmt(ms):
    return "-" if ms is None else f"{ms:.1f}ms"


def main():
    bench = Bench(parse_args())
    knee, runs = bench.find()
    bench.save(knee, runs)


if __name__ == "__main__":
    main()
