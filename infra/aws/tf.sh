#!/usr/bin/env bash
# Runs Terraform in Docker (no local install). Uses ~/.aws credentials read-only.
#   infra/aws/tf.sh init | plan | apply | destroy ...
set -euo pipefail
cd "$(dirname "$0")"
exec docker run --rm -i \
  -v "$PWD":/work -w /work \
  -v "$HOME/.aws":/root/.aws:ro \
  -e AWS_PROFILE -e AWS_REGION -e TF_IN_AUTOMATION=1 \
  hashicorp/terraform:1.12.2 "$@"
