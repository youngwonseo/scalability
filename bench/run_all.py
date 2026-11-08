#!/usr/bin/env python3
"""Run the whole cloud experiment unattended, then tear everything down (EXPERIMENT_PLAN.md 5).

  python3 bench/run_all.py --plan rehearsal            # print the plan and cost estimate only
  python3 bench/run_all.py --plan rehearsal --dry-run  # print every command, touch nothing
  python3 bench/run_all.py --plan rehearsal --yes      # really run (creates EC2 instances!)
  python3 bench/run_all.py --plan full --yes --only s1,s2

For each step: terraform apply with the step's host counts -> configure roles over SSH -> (re)load data
-> start apps, LB, monitoring targets -> run find_limit.py on the load generator for each workload.
Results are copied to results/cloud-<timestamp>/ after every step. Whatever happens, the finally block
copies results, runs terraform destroy and checks that no instance is left (unless --keep).

Run it inside `caffeinate -i` (macOS) so the laptop does not sleep; workloads run detached on the load
generator, so a short network drop does not kill a measurement.
"""

import argparse
import json
import shlex
import subprocess
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import scenarios  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
TF_DIR = ROOT / "infra" / "aws"
REMOTE = TF_DIR / "remote"
JAR = ROOT / "app" / "build" / "libs" / "sns.jar"
JMX_AGENT_URL = ("https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/1.0.1/"
                 "jmx_prometheus_javaagent-1.0.1.jar")
K6_DOCKER = ("docker run --rm -i --network host --ulimit nofile=1048576:1048576 "
             "-v /opt/bench/k6:/scripts:ro -v /opt/bench/results:/results "
             "-e K6_PROMETHEUS_RW_SERVER_URL=http://127.0.0.1:9090/api/v1/write "
             "-e 'K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99)' grafana/k6:1.1.0")
MIN_POOL = 2  # small enough that apps x pool stays within the DB budget even with 8 apps
DEFAULT_DB_BUDGET = 40


class Log:
    def __init__(self):
        self.file = None

    def open(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.file = open(path, "a")

    def __call__(self, message):
        line = f"[{datetime.now():%H:%M:%S}] {message}"
        print(line, flush=True)
        if self.file:
            self.file.write(line + "\n")
            self.file.flush()


log = Log()


class Cloud:
    """Terraform + SSH. In dry-run mode every command is printed and fake hosts are returned."""

    def __init__(self, operator_cidr, dry_run):
        self.operator_cidr = operator_cidr
        self.dry_run = dry_run
        self.counts = {}
        self.key = TF_DIR / ".ssh" / "id_ed25519"

    # --- terraform ---

    def tf(self, *args, capture=False):
        cmd = [str(TF_DIR / "tf.sh"), *args]
        if self.dry_run:
            log(f"DRY terraform {' '.join(args)}")
            return ""
        result = subprocess.run(cmd, check=True, text=True, capture_output=capture)
        return result.stdout if capture else ""

    def tf_vars(self):
        out = ["-var", f"operator_cidr={self.operator_cidr}"]
        for k, v in self.counts.items():
            out += ["-var", f"{k}={v}"]
        return out

    def apply(self, counts):
        if counts == self.counts:
            return
        self.counts = dict(counts)
        log(f"terraform apply {counts}")
        self.tf("apply", "-auto-approve", "-input=false", *self.tf_vars())

    def destroy(self):
        log("terraform destroy")
        self.tf("destroy", "-auto-approve", "-input=false", *self.tf_vars())

    def hosts(self):
        if self.dry_run:
            hosts, n = {}, 10
            roles = {"app": self.counts.get("app_count", 0), "mysql": self.counts.get("mysql_count", 0),
                     "cassandra": self.counts.get("cassandra_count", 0), "redis": self.counts.get("redis_count", 0),
                     "lb": 1, "loadgen": 1}
            for role, count in roles.items():
                for i in range(1, count + 1):
                    n += 1
                    hosts[f"{role}-{i}"] = {"role": role, "index": i, "public_ip": f"203.0.113.{n}",
                                            "private_ip": f"10.42.1.{n}"}
            return hosts
        return json.loads(self.tf("output", "-json", "hosts", capture=True))

    # --- ssh ---

    def ssh_cmd(self, host):
        return ["ssh", "-i", str(self.key), "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
                "-o", "LogLevel=ERROR", "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=30",
                f"ec2-user@{host['public_ip']}"]

    def ssh(self, host, command, stdin=None, check=True, quiet=False):
        if self.dry_run:
            if not quiet:
                log(f"DRY ssh {host['name']}: {command}")
            return fake_output(command)
        result = subprocess.run(self.ssh_cmd(host) + [command], input=stdin, text=True, capture_output=True)
        if check and result.returncode != 0:
            raise RuntimeError(f"ssh {host['name']} failed ({result.returncode}): {command}\n"
                               f"{result.stdout[-2000:]}{result.stderr[-2000:]}")
        return result.stdout

    def script(self, host, name, *args, env=None):
        """Runs infra/aws/remote/<name> as root on the host."""
        env_part = " ".join(f"{k}={shlex.quote(str(v))}" for k, v in (env or {}).items())
        command = f"sudo {env_part} bash -s -- {' '.join(shlex.quote(str(a)) for a in args)}"
        return self.ssh(host, command, stdin=(REMOTE / name).read_text())

    def upload(self, host, local_path, remote_path):
        if self.dry_run:
            log(f"DRY upload {local_path} -> {host['name']}:{remote_path}")
            return
        subprocess.run(["scp", "-i", str(self.key), "-o", "StrictHostKeyChecking=no", "-o",
                        "UserKnownHostsFile=/dev/null", "-o", "LogLevel=ERROR", str(local_path),
                        f"ec2-user@{host['public_ip']}:{remote_path}"], check=True)


def fake_output(command):
    if "result.json" in command:
        return json.dumps({"max_rps": 1000})
    if "echo $!" in command:
        return "4242\n"
    if "kill -0" in command:
        return "done 0\n"
    if "COUNT(*)" in command:
        return "100\n"
    return ""


class Experiment:
    def __init__(self, plan, cloud, args):
        self.plan = plan
        self.cloud = cloud
        self.args = args
        self.out = ROOT / "results" / f"cloud-{datetime.now():%Y%m%d-%H%M%S}"
        self.hosts = {}
        self.bootstrapped = set()   # hosts that have the jar / JMX agent / repo files
        self.mysql_roles = {}       # host name -> role currently configured
        self.storage_signature = None
        self.loaded = False
        self.db_budget = None       # per-DB-node connection budget from the pool sweep
        self.sweep = {}             # pool -> {workload: max_rps}
        self.best = {}              # workload -> best max_rps so far (start rate for the next search)

    # --- helpers ---

    def by_role(self, role):
        return sorted((h for h in self.hosts.values() if h["role"] == role), key=lambda h: h["index"])

    @property
    def loadgen(self):
        return self.hosts["loadgen-1"]

    def parallel(self, fn, items):
        items = list(items)
        if not items:
            return []
        with ThreadPoolExecutor(max_workers=len(items)) as pool:
            return list(pool.map(fn, items))

    # --- infrastructure ---

    def provision(self, topology):
        self.cloud.apply(topology.counts())
        self.hosts = {name: {**h, "name": name} for name, h in self.cloud.hosts().items()}
        self.mysql_roles = {k: v for k, v in self.mysql_roles.items() if k in self.hosts}
        self.bootstrapped &= set(self.hosts)
        self.parallel(self.wait_ready, self.hosts.values())
        if "loadgen-1" not in self.bootstrapped:
            self.bootstrap_loadgen()
        self.parallel(self.bootstrap_app, [h for h in self.by_role("app") if h["name"] not in self.bootstrapped])
        self.parallel(self.bootstrap_cassandra,
                      [h for h in self.by_role("cassandra") if h["name"] not in self.bootstrapped])

    def wait_ready(self, host):
        """SSH up and user_data finished (docker installed, images pulled)."""
        for _ in range(90):
            try:
                if "ready" in self.cloud.ssh(host, "test -f /var/lib/scalability-ready && echo ready",
                                             check=False, quiet=True) or self.cloud.dry_run:
                    return
            except Exception:
                pass
            time.sleep(10)
        raise RuntimeError(f"{host['name']} did not become ready")

    def bootstrap_loadgen(self):
        lg = self.loadgen
        log("bootstrapping load generator (repo files, jar, monitoring)")
        archive = self.out / "bench.tar.gz"
        archive.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["tar", "czf", str(archive), "-C", str(ROOT), "bench", "k6", "infra/local/grafana",
                        "infra/aws/remote"], check=True)
        self.cloud.upload(lg, archive, "/tmp/bench.tar.gz")
        self.cloud.upload(lg, JAR, "/tmp/sns.jar")
        self.cloud.ssh(lg, "sudo mkdir -p /opt/bench/results /opt/bench/logs /opt/sns && "
                           "sudo tar xzf /tmp/bench.tar.gz -C /opt/bench && sudo cp /tmp/sns.jar /opt/sns/sns.jar && "
                           "sudo rm -rf /opt/sns/app && sudo docker run --rm -v /opt/sns:/opt/sns eclipse-temurin:25-jre "
                           "java -Djarmode=tools -jar /opt/sns/sns.jar extract --destination /opt/sns/app")
        self.cloud.script(lg, "monitoring.sh")
        self.bootstrapped.add("loadgen-1")

    def bootstrap_app(self, host):
        self.cloud.upload(host, JAR, "/tmp/sns.jar")
        self.cloud.ssh(host, "sudo mkdir -p /opt/sns && sudo cp /tmp/sns.jar /opt/sns/sns.jar")
        self.bootstrapped.add(host["name"])

    def bootstrap_cassandra(self, host):
        self.cloud.upload(host, TF_DIR.parent / "local" / "cassandra" / "jmx-exporter.yml", "/tmp/jmx.yml")
        self.cloud.ssh(host, f"sudo mkdir -p /opt/jmx && sudo cp /tmp/jmx.yml /opt/jmx/config.yml && "
                             f"sudo curl -sfL -o /opt/jmx/agent.jar {JMX_AGENT_URL}")
        self.bootstrapped.add(host["name"])

    # --- storage ---

    def configure_storage(self, step):
        topology = step.topology
        fresh = step.fresh_storage or topology.storage_signature != self.storage_signature
        if fresh:
            self.mysql_roles = {}
            self.loaded = False
        if topology.storage in ("mysql", "sharded"):
            self.configure_mysql(topology, fresh)
        elif topology.storage == "cassandra" and fresh:
            self.configure_cassandra()
        if topology.cache:
            redis = self.by_role("redis")[0]
            if redis["name"] not in self.bootstrapped:
                self.cloud.script(redis, "redis.sh")
                self.bootstrapped.add(redis["name"])
        self.storage_signature = topology.storage_signature
        if not self.loaded:
            self.load(topology)
            self.loaded = True
        if topology.replicas:
            self.wait_replicas()
        self.set_buffer_pool(topology, step.buffer_pool)
        if topology.cache:
            self.cloud.ssh(self.by_role("redis")[0], "sudo docker exec redis redis-cli FLUSHALL")

    def configure_mysql(self, topology, fresh):
        nodes = self.by_role("mysql")
        primary = nodes[0]
        wanted = {}
        for i, node in enumerate(nodes):
            if topology.storage == "sharded":
                wanted[node["name"]] = ("shard",)
            elif i == 0:
                wanted[node["name"]] = ("primary",)
            else:
                wanted[node["name"]] = ("replica", primary["private_ip"])
        todo = [n for n in nodes if self.mysql_roles.get(n["name"]) != wanted[n["name"]]]
        if not todo:
            return
        log(f"configuring MySQL: {', '.join(n['name'] + '=' + wanted[n['name']][0] for n in todo)}")
        reset = {"RESET": 1}

        def configure(node):
            self.cloud.script(node, "mysql.sh", wanted[node["name"]][0], 100 + node["index"],
                              *wanted[node["name"]][1:], env=reset)
            self.mysql_roles[node["name"]] = wanted[node["name"]]

        # the primary first so new replicas can connect to it
        if primary in todo:
            configure(primary)
        self.parallel(configure, [n for n in todo if n is not primary])

    def configure_cassandra(self):
        nodes = self.by_role("cassandra")
        seed = nodes[0]["private_ip"]
        log(f"starting a fresh {len(nodes)}-node Cassandra cluster")
        for node in nodes:  # nodes must bootstrap one at a time
            self.cloud.script(node, "cassandra.sh", seed, env={"RESET": 1})

    def load(self, topology):
        p = self.plan
        log(f"loading {p.users} users / {p.posts} posts into {topology.storage}")
        env, profiles = self.storage_env(topology), "loader"
        if topology.storage == "cassandra":
            profiles += ",cassandra"
        elif topology.storage == "sharded":
            profiles += ",sharded"
        env_args = " ".join(f"-e {k}={shlex.quote(v)}" for k, v in env.items() if k != "DB_REPLICA_URLS")
        self.cloud.ssh(self.loadgen,
                       f"sudo docker run --rm --network host {env_args} -v /opt/sns/app:/app:ro -w /app "
                       f"eclipse-temurin:25-jre java -jar sns.jar --spring.profiles.active={profiles} "
                       f"--sns.loader.users={p.users} --sns.loader.posts={p.posts} --sns.loader.cassandra-in-flight=1024 "
                       f"> /tmp/load.log 2>&1 || (tail -50 /tmp/load.log; exit 1)")

    def wait_replicas(self):
        """Measure only after every replica has applied everything the primary has."""
        primary, replicas = self.by_role("mysql")[0], self.by_role("mysql")[1:]
        count = "sudo docker exec mysql mysql -uroot -proot -N -e 'SELECT COUNT(*) FROM sns.posts' 2>/dev/null"
        for _ in range(360):
            target = self.cloud.ssh(primary, count, quiet=True).strip()
            counts = [self.cloud.ssh(r, count, check=False, quiet=True).strip() for r in replicas]
            if all(c == target for c in counts):
                log(f"replicas caught up ({target} posts)")
                return
            time.sleep(10)
        raise RuntimeError("replicas did not catch up within an hour")

    def set_buffer_pool(self, topology, size):
        if topology.storage != "mysql":
            return
        value = size or "10G"
        bytes_ = int(value[:-1]) * {"M": 1 << 20, "G": 1 << 30}[value[-1]]
        self.parallel(lambda n: self.cloud.ssh(
            n, f"sudo docker exec mysql mysql -uroot -proot -e 'SET GLOBAL innodb_buffer_pool_size = {bytes_}' "
               "2>/dev/null"), self.by_role("mysql"))

    def storage_env(self, topology):
        env = {}
        mysql = self.by_role("mysql")
        if topology.storage == "mysql":
            env["DB_URL"] = f"jdbc:mysql://{mysql[0]['private_ip']}:3306/sns"
            env["DB_REPLICA_URLS"] = ",".join(f"jdbc:mysql://{n['private_ip']}:3306/sns" for n in mysql[1:])
        elif topology.storage == "sharded":
            env["DB_SHARD_URLS"] = ",".join(f"jdbc:mysql://{n['private_ip']}:3306/sns" for n in mysql)
        elif topology.storage == "cassandra":
            nodes = self.by_role("cassandra")
            env["CASSANDRA_CONTACT_POINTS"] = ",".join(f"{n['private_ip']}:9042" for n in nodes)
            env["CASSANDRA_RF"] = str(min(3, len(nodes)))
        return env

    # --- apps, LB, monitoring ---

    def pool_size(self, step):
        if step.pool:
            return step.pool
        budget = self.db_budget or DEFAULT_DB_BUDGET
        return max(MIN_POOL, budget // step.topology.apps)

    def configure_apps(self, step):
        topology = step.topology
        pool = self.pool_size(step)
        base = {**self.storage_env(topology), "DB_POOL_SIZE": str(pool),
                "CACHE_ENABLED": str(topology.cache).lower()}
        if topology.cache:
            base["REDIS_HOST"] = self.by_role("redis")[0]["private_ip"]
        if topology.storage in ("cassandra", "sharded"):
            base["SPRING_PROFILES_ACTIVE"] = topology.storage
        log(f"starting {topology.apps} apps (pool/app={pool})")

        def start(app):
            env = {**base, "WORKER_ID": str(app["index"])}
            self.cloud.ssh(app, "sudo tee /opt/sns/app.env > /dev/null",
                           stdin="".join(f"{k}={v}\n" for k, v in env.items()))
            self.cloud.script(app, "app.sh")

        self.parallel(start, self.by_role("app"))
        self.cloud.script(self.hosts["lb-1"], "lb.sh", *[a["private_ip"] for a in self.by_role("app")])

    def write_targets(self):
        def targets(role, port, extra=None):
            return [{"targets": [f"{h['private_ip']}:{port}"],
                     "labels": {"host": h["name"], "role": role, **(extra(h) if extra else {})}}
                    for h in self.by_role(role)]

        files = {
            "node": [{"targets": [f"{h['private_ip']}:9100"], "labels": {"host": h["name"], "role": h["role"]}}
                     for h in self.hosts.values()],
            "app": targets("app", 8080, lambda h: {"instance": h["name"]}),
            "mysql": [{"targets": [f"{h['private_ip']}:9104"],
                       "labels": {"host": h["name"], "node": h["name"], "role": self.mysql_roles.get(h["name"], ("?",))[0]}}
                      for h in self.by_role("mysql")],
            "cassandra": targets("cassandra", 7070, lambda h: {"instance": h["name"]}),
            "redis": targets("redis", 9121),
        }
        for job, content in files.items():
            self.cloud.ssh(self.loadgen, f"sudo tee /opt/mon/targets/{job}.json > /dev/null",
                           stdin=json.dumps(content), quiet=True)

    # --- measurement ---

    def bench(self, step, run):
        label = step.label + run.tag
        start = max(self.plan.start_rate, int(self.best.get(run.workload, 0) * 0.5))
        d = self.plan.durations
        args = ["--label", label, "--workload", run.workload, "--users", self.plan.users,
                "--posts", self.plan.posts, "--start", start,
                "--warmup-duration", d["warmup"], "--coarse-duration", d["coarse"],
                "--fine-duration", d["fine"], "--confirm-duration", d["confirm"]]
        if run.dist:
            args += ["--dist", run.dist]
        if run.raw_rate:
            args += ["--raw-rate", run.raw_rate]
        if run.coarse_only:
            args += ["--coarse-only"]
        env = {"PROM_URL": "http://127.0.0.1:9090", "LOADGEN_HOST": "loadgen-1",
               "BASE_URL": f"http://{self.hosts['lb-1']['private_ip']}",
               "K6_CMD": K6_DOCKER, "K6_SCRIPT": "/scripts/workload.js", "K6_RESULTS_DIR": "/results"}
        command = (" ".join(f"{k}={shlex.quote(v)}" for k, v in env.items())
                   + " python3 bench/find_limit.py " + " ".join(shlex.quote(str(a)) for a in args))
        logfile = f"/opt/bench/logs/{label}-{run.workload}.log"
        log(f"--> {label} {run.workload} (start {start}/s)")
        # detached (as root, like the rest of /opt/bench), so an SSH drop does not kill the measurement
        inner = f"{command}; echo $? > {logfile}.exit"
        outer = f"cd /opt/bench && nohup bash -c {shlex.quote(inner)} > {logfile} 2>&1 < /dev/null & echo $!"
        pid = self.cloud.ssh(self.loadgen, f"sudo bash -c {shlex.quote(outer)}").strip()
        seen = 0
        while True:
            status = self.cloud.ssh(self.loadgen, f"if sudo kill -0 {pid} 2>/dev/null; then echo running; "
                                                  f"else echo done $(cat {logfile}.exit 2>/dev/null); fi",
                                    check=False, quiet=True).strip()
            lines = self.cloud.ssh(self.loadgen, f"sudo tail -n +{seen + 1} {logfile} 2>/dev/null",
                                   check=False, quiet=True).splitlines()
            for line in lines:
                log("    " + line)
            seen += len(lines)
            if status.startswith("done"):
                break
            time.sleep(20)
        code = status.split()[1] if len(status.split()) > 1 else "?"
        if code != "0":
            log(f"!! {label} {run.workload} did not produce a result (exit {code}); continuing")
            return None
        result = json.loads(self.cloud.ssh(self.loadgen, f"sudo cat /opt/bench/results/{label}/{run.workload}/result.json",
                                           quiet=True))
        self.best[run.workload] = max(self.best.get(run.workload, 0), result["max_rps"])
        return result

    def fetch_results(self):
        if "loadgen-1" not in self.hosts:
            return
        if self.cloud.dry_run:
            log("DRY fetch results")
            return
        self.out.mkdir(parents=True, exist_ok=True)
        tar = subprocess.run(self.cloud.ssh_cmd(self.loadgen) + ["sudo tar czf - -C /opt/bench results logs"],
                             capture_output=True)
        if tar.returncode == 0:
            subprocess.run(["tar", "xzf", "-", "-C", str(self.out)], input=tar.stdout, check=False)
            log(f"results copied to {self.out}")

    # --- main loop ---

    def run(self, only):
        for step in self.plan.steps:
            if only and not any(step.label.startswith(o) for o in only):
                continue
            if not step.pool_sweep and self.sweep and self.db_budget is None:
                self.choose_budget()
            log(f"=== {step.label}: {step.topology} (~${scenarios.hourly_cost(step.topology):.2f}/h)")
            self.provision(step.topology)
            self.configure_storage(step)
            self.configure_apps(step)
            self.write_targets()
            for run in step.runs:
                result = self.bench(step, run)
                if result and step.pool_sweep:
                    self.sweep.setdefault(step.pool, {})[run.workload] = result["max_rps"]
            self.fetch_results()
        if self.sweep and self.db_budget is None:
            self.choose_budget()

    def choose_budget(self):
        """Per-DB-node connection budget = the swept pool size with the best R1 and W1 throughput combined."""
        best = {w: max(r.get(w, 0) for r in self.sweep.values()) or 1 for w in ("R1", "W1")}
        score = {pool: sum(r.get(w, 0) / best[w] for w in best) for pool, r in self.sweep.items()}
        self.db_budget = max(score, key=score.get)
        log(f"pool sweep {self.sweep} -> DB connection budget {self.db_budget}")
        (self.out / "db_budget.json").parent.mkdir(parents=True, exist_ok=True)
        (self.out / "db_budget.json").write_text(json.dumps({"sweep": self.sweep, "budget": self.db_budget}))


def remaining_instances(dry_run):
    if dry_run:
        return []
    out = subprocess.run(["aws", "ec2", "describe-instances", "--region", "ap-northeast-2", "--filters",
                          "Name=tag:Project,Values=scalability-experiment",
                          "Name=instance-state-name,Values=pending,running,stopping,stopped",
                          "--query", "Reservations[].Instances[].InstanceId", "--output", "json"],
                         capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def describe(plan):
    total_hours, total_cost = 0.0, 0.0
    print(f"plan '{plan.name}': {plan.users} users / {plan.posts} posts, durations {plan.durations}")
    for step in plan.steps:
        # rough: full search ~20 min per workload, coarse-only ~6 min, plus ~10 min setup per step
        minutes = 10 + sum(6 if r.coarse_only else (20 if plan.name == "full" else 4) for r in step.runs)
        hours = minutes / 60
        cost = hours * scenarios.hourly_cost(step.topology)
        total_hours, total_cost = total_hours + hours, total_cost + cost
        print(f"  {step.label:12} {str(step.topology.counts()):70} {len(step.runs)} runs  ~{hours:.1f}h  ~${cost:.2f}")
    print(f"total ~{total_hours:.1f}h, ~${total_cost:.0f} (+ EBS/IP/transfer, rough estimate)")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--plan", choices=["rehearsal", "full"], required=True)
    p.add_argument("--only", help="comma-separated step label prefixes, e.g. s1,s2")
    p.add_argument("--yes", action="store_true", help="really create AWS resources")
    p.add_argument("--dry-run", action="store_true", help="print commands without running anything")
    p.add_argument("--keep", action="store_true", help="do not destroy at the end (debugging)")
    p.add_argument("--skip-build", action="store_true")
    args = p.parse_args()

    plan = scenarios.full_plan() if args.plan == "full" else scenarios.rehearsal_plan()
    describe(plan)
    if not args.yes and not args.dry_run:
        print("\nNothing was created. Re-run with --dry-run to see every command, or --yes to run for real.")
        return

    if not args.skip_build and not args.dry_run:
        subprocess.run([str(ROOT / "app" / "gradlew"), "-q", "-p", str(ROOT / "app"), "bootJar"], check=True)
    operator_ip = "198.51.100.1" if args.dry_run else urllib.request.urlopen(
        "https://checkip.amazonaws.com", timeout=10).read().decode().strip()
    cloud = Cloud(f"{operator_ip}/32", args.dry_run)
    experiment = Experiment(plan, cloud, args)
    log.open(experiment.out / "run_all.log")
    log(f"operator {operator_ip}, results -> {experiment.out}")
    if not args.dry_run:
        cloud.tf("init", "-input=false")

    failed = False
    try:
        experiment.run([o.strip() for o in args.only.split(",")] if args.only else None)
    except BaseException as e:  # including Ctrl-C: always clean up
        failed = True
        log(f"!! aborted: {e!r}")
        raise
    finally:
        try:
            experiment.fetch_results()
        except Exception as e:
            log(f"!! could not fetch results: {e!r}")
        if args.keep:
            log("--keep: instances are still running (and billing). Destroy with: infra/aws/tf.sh destroy")
        else:
            experiment.cloud.destroy()
            left = remaining_instances(args.dry_run)
            log("all instances terminated" if not left else f"!! instances still present: {left}")
        log("finished with errors" if failed else "finished")


if __name__ == "__main__":
    main()
