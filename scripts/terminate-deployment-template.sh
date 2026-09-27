#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$DIR/config.sh"

echo "Deleting CloudWatch alarms..."
aws cloudwatch delete-alarms \
  --alarm-names "$HIGH_CPU_ALARM_NAME" "$LOW_CPU_ALARM_NAME" || true

echo "Deleting scaling policies..."
aws autoscaling delete-policy \
  --auto-scaling-group-name "$ASG_NAME" \
  --policy-name "$HIGH_CPU_POLICY_NAME" || true

aws autoscaling delete-policy \
  --auto-scaling-group-name "$ASG_NAME" \
  --policy-name "$LOW_CPU_POLICY_NAME" || true

echo "Scaling ASG down to zero..."
aws autoscaling update-auto-scaling-group \
  --auto-scaling-group-name "$ASG_NAME" \
  --min-size 0 \
  --max-size 0 \
  --desired-capacity 0 || true

sleep 60

echo "Deleting ASG..."
aws autoscaling delete-auto-scaling-group \
  --auto-scaling-group-name "$ASG_NAME" \
  --force-delete || true

echo "Deleting launch template..."
aws ec2 delete-launch-template \
  --launch-template-name "$LAUNCH_TEMPLATE_NAME" || true

echo "Deleting load balancer..."
aws elb delete-load-balancer \
  --load-balancer-name "$ELB_NAME" || true

echo "Deployment terminated."
