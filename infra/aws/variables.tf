variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "availability_zone" {
  type    = string
  default = "ap-northeast-2a"
}

variable "operator_cidr" {
  description = "Your public IP in CIDR form (SSH and Grafana are open to it only), e.g. 1.2.3.4/32"
  type        = string
}

# Number of hosts per role. run_all.py changes these per scenario; everything else stays fixed.
variable "app_count" {
  type    = number
  default = 1
}

variable "mysql_count" {
  description = "MySQL hosts: node 1 is the primary (or shard 1); the rest are replicas or shards"
  type        = number
  default     = 1
}

variable "cassandra_count" {
  type    = number
  default = 0
}

variable "redis_count" {
  type    = number
  default = 0
}

# Fixed instance sizes (EXPERIMENT_PLAN.md 5). t-family instances are avoided on purpose: CPU credits.
variable "instance_types" {
  type = map(string)
  default = {
    app       = "c6i.large"  # 2 vCPU / 4 GB
    mysql     = "m6i.xlarge" # 4 vCPU / 16 GB
    cassandra = "m6i.xlarge"
    redis     = "m6i.large"
    lb        = "c6i.xlarge"
    loadgen   = "c6i.2xlarge" # k6 + Prometheus + Grafana
  }
}

variable "auto_terminate_minutes" {
  description = "Every instance shuts itself down (and is terminated) after this long, even if the run script dies"
  type        = number
  default     = 1440
}
