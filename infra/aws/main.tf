terraform {
  required_version = ">= 1.6"
  required_providers {
    aws   = { source = "hashicorp/aws", version = "~> 5.0" }
    tls   = { source = "hashicorp/tls", version = "~> 4.0" }
    local = { source = "hashicorp/local", version = "~> 2.0" }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = "scalability-experiment" }
  }
}

# Latest Amazon Linux 2023 (x86_64). Looked up via EC2 rather than SSM, which needs extra IAM permissions.
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-kernel-*-x86_64"]
  }
  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
}

# --- network: one VPC, one public subnet in one AZ ---------------------------------------------

resource "aws_vpc" "main" {
  cidr_block           = "10.42.0.0/16"
  enable_dns_hostnames = true
  tags                 = { Name = "scalability" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
}

resource "aws_subnet" "main" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.42.1.0/24"
  availability_zone       = var.availability_zone
  map_public_ip_on_launch = true
}

resource "aws_route_table" "main" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
}

resource "aws_route_table_association" "main" {
  subnet_id      = aws_subnet.main.id
  route_table_id = aws_route_table.main.id
}

resource "aws_security_group" "main" {
  name   = "scalability"
  vpc_id = aws_vpc.main.id

  ingress {
    description = "all traffic between experiment hosts"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }
  ingress {
    description = "SSH from the operator"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.operator_cidr]
  }
  ingress {
    description = "Grafana from the operator"
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = [var.operator_cidr]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# --- SSH key generated per run, written next to this file (gitignored) ------------------------

resource "tls_private_key" "ssh" {
  algorithm = "ED25519"
}

resource "aws_key_pair" "ssh" {
  key_name   = "scalability"
  public_key = tls_private_key.ssh.public_key_openssh
}

resource "local_sensitive_file" "ssh_key" {
  filename        = "${path.module}/.ssh/id_ed25519"
  content         = tls_private_key.ssh.private_key_openssh
  file_permission = "0600"
}

# --- hosts ---------------------------------------------------------------------------------------

locals {
  roles = {
    app       = { count = var.app_count, disk_gb = 30 }
    mysql     = { count = var.mysql_count, disk_gb = 100 }
    cassandra = { count = var.cassandra_count, disk_gb = 100 }
    redis     = { count = var.redis_count, disk_gb = 30 }
    lb        = { count = 1, disk_gb = 30 }
    loadgen   = { count = 1, disk_gb = 50 }
  }
  hosts = merge([
    for role, cfg in local.roles : {
      for i in range(cfg.count) : "${role}-${i + 1}" => { role = role, index = i + 1, disk_gb = cfg.disk_gb }
    }
  ]...)
}

resource "aws_instance" "host" {
  for_each = local.hosts

  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_types[each.value.role]
  subnet_id              = aws_subnet.main.id
  vpc_security_group_ids = [aws_security_group.main.id]
  key_name               = aws_key_pair.ssh.key_name

  # "shutdown -h" from the safety timer terminates instead of just stopping
  instance_initiated_shutdown_behavior = "terminate"

  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    role                   = each.value.role
    auto_terminate_minutes = var.auto_terminate_minutes
  })

  root_block_device {
    volume_type = "gp3"
    volume_size = each.value.disk_gb
    iops        = 3000 # gp3 baseline, fixed so every run sees the same disk
    throughput  = 125
  }

  tags = { Name = "scalability-${each.key}", Role = each.value.role }
}
