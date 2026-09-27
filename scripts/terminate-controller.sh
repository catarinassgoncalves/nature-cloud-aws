#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$DIR/config.sh"

CONTROLLER_NAME="${CONTROLLER_NAME:-NatureCloud-Controller}"

if [ -f "$DIR/controller.id" ]; then
  CONTROLLER_ID="$(cat "$DIR/controller.id")"

  echo "Terminating controller from controller.id: $CONTROLLER_ID"
  aws ec2 terminate-instances --instance-ids "$CONTROLLER_ID" > /dev/null || true
  aws ec2 wait instance-terminated --instance-ids "$CONTROLLER_ID" || true

  rm -f "$DIR/controller.id" "$DIR/controller.ip" "$DIR/controller.dns"
  echo "Controller terminated."
  exit 0
fi

echo "controller.id not found. Searching by tag..."

CONTROLLER_IDS=$(
  aws ec2 describe-instances \
    --filters "Name=tag:Name,Values=$CONTROLLER_NAME" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query "Reservations[*].Instances[*].InstanceId" \
    --output text
)

if [ -z "$CONTROLLER_IDS" ]; then
  echo "No controller instances found."
  exit 0
fi

echo "Terminating controller instances: $CONTROLLER_IDS"
aws ec2 terminate-instances --instance-ids $CONTROLLER_IDS > /dev/null
aws ec2 wait instance-terminated --instance-ids $CONTROLLER_IDS || true

rm -f "$DIR/controller.id" "$DIR/controller.ip" "$DIR/controller.dns"

echo "Controller instances terminated."