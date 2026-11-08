"""Scenario plans for bench/run_all.py (EXPERIMENT_PLAN.md 4.4)."""

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Topology:
    apps: int
    storage: str = "mysql"  # mysql | cassandra | sharded
    replicas: int = 0       # S3/S4
    cache: bool = False     # S4
    cassandra_nodes: int = 0  # S5
    shards: int = 0         # S6

    @property
    def mysql_hosts(self):
        return {"mysql": 1 + self.replicas, "sharded": self.shards}.get(self.storage, 0)

    @property
    def storage_signature(self):
        """Changing this requires wiping and reloading the storage. Adding replicas does not."""
        return (self.storage, self.cassandra_nodes, self.shards)

    def counts(self):
        return {
            "app_count": self.apps,
            "mysql_count": self.mysql_hosts,
            "cassandra_count": self.cassandra_nodes,
            "redis_count": 1 if self.cache else 0,
        }


@dataclass(frozen=True)
class Run:
    workload: str
    dist: str | None = None
    raw_rate: int = 0
    coarse_only: bool = False
    tag: str = ""  # appended to the step label in results/


@dataclass
class Step:
    label: str
    topology: Topology
    runs: list
    fresh_storage: bool = False  # wipe and reload before this step (resets data grown by writes)
    pool: int | None = None      # per-app pool size override (S1 pool sweep)
    pool_sweep: bool = False     # result feeds the DB connection budget for later steps
    buffer_pool: str | None = None  # InnoDB buffer pool override (S1 disk-bound variant)


@dataclass
class Plan:
    name: str
    users: int
    posts: int
    steps: list
    durations: dict = field(default_factory=dict)
    start_rate: int = 100


ALL = ["R1", "R2", "W1", "W2", "M1", "M2", "M3"]


def full_plan():
    one, four = Topology(apps=1), Topology(apps=4)
    steps = []
    # S1: pool sweep (sets the per-DB-node connection budget), then all workloads, then disk-bound reads
    for i, pool in enumerate([10, 20, 40, 80]):
        steps.append(Step(f"s1-pool{pool}", one, [Run("R1", coarse_only=True), Run("W1", coarse_only=True)],
                          fresh_storage=i == 0, pool=pool, pool_sweep=True))
    steps.append(Step("s1", one, [Run(w) for w in ALL], fresh_storage=True))
    steps.append(Step("s1-smallbp", one, [Run("R1"), Run("R2")], buffer_pool="512M"))
    # S2: app scale-out on one MySQL
    steps.append(Step("s2-app2", Topology(apps=2), [Run("R1"), Run("W1")], fresh_storage=True))
    steps.append(Step("s2-app4", four, [Run("R1"), Run("W1"), Run("M1")]))
    steps.append(Step("s2-app8", Topology(apps=8), [Run("R1"), Run("W1")]))
    # S3: read replicas (+ read-after-write probe)
    steps.append(Step("s3-rep1", Topology(apps=4, replicas=1), [Run("R1"), Run("R2")], fresh_storage=True))
    steps.append(Step("s3-rep2", Topology(apps=4, replicas=2),
                      [Run("R1"), Run("R2"), Run("W1"), Run("M1", raw_rate=20), Run("M2", raw_rate=20)]))
    # S4: cache in front of S3; key distribution decides the hit ratio
    steps.append(Step("s4", Topology(apps=4, replicas=2, cache=True),
                      [Run("R1"), Run("R1", dist="zipf", tag="-zipf"), Run("M1"), Run("M1", dist="zipf", tag="-zipf")]))
    # S5: Cassandra, fresh 3- and 6-node clusters
    steps.append(Step("s5-c3", Topology(apps=4, storage="cassandra", cassandra_nodes=3),
                      [Run("R1"), Run("W1")], fresh_storage=True))
    steps.append(Step("s5-c6", Topology(apps=4, storage="cassandra", cassandra_nodes=6),
                      [Run("R1"), Run("W1"), Run("R2"), Run("M3")], fresh_storage=True))
    # S6: MySQL sharding, same workloads as S5 for comparison
    steps.append(Step("s6-sh2", Topology(apps=4, storage="sharded", shards=2),
                      [Run("R1"), Run("W1")], fresh_storage=True))
    steps.append(Step("s6-sh4", Topology(apps=4, storage="sharded", shards=4),
                      [Run("R1"), Run("W1"), Run("W2"), Run("M3")], fresh_storage=True))
    return Plan("full", users=1_000_000, posts=10_000_000, steps=steps, durations={
        "warmup": "60s", "coarse": "30s", "fine": "60s", "confirm": "5m"})


def rehearsal_plan():
    """Touches every storage path once with a tiny dataset and short steps (~1 hour, ~$2)."""
    steps = [
        Step("r-s1", Topology(apps=1), [Run("R1")], fresh_storage=True, pool=20, pool_sweep=True),
        Step("r-s3", Topology(apps=2, replicas=1), [Run("M2", raw_rate=10)], fresh_storage=True),
        Step("r-s4", Topology(apps=2, replicas=1, cache=True), [Run("R1", dist="zipf")]),
        Step("r-s5", Topology(apps=1, storage="cassandra", cassandra_nodes=1), [Run("M2")], fresh_storage=True),
        Step("r-s6", Topology(apps=1, storage="sharded", shards=2), [Run("M3")], fresh_storage=True),
    ]
    return Plan("rehearsal", users=10_000, posts=100_000, steps=steps, start_rate=50, durations={
        "warmup": "20s", "coarse": "10s", "fine": "10s", "confirm": "15s"})


# Approximate on-demand $/hour in ap-northeast-2 (re-check before running)
HOURLY_USD = {"app": 0.096, "mysql": 0.236, "cassandra": 0.236, "redis": 0.118, "lb": 0.192, "loadgen": 0.384}


def hourly_cost(topology):
    c = topology.counts()
    return (c["app_count"] * HOURLY_USD["app"] + c["mysql_count"] * HOURLY_USD["mysql"]
            + c["cassandra_count"] * HOURLY_USD["cassandra"] + c["redis_count"] * HOURLY_USD["redis"]
            + HOURLY_USD["lb"] + HOURLY_USD["loadgen"])
