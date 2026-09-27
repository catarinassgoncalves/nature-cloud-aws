#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$DIR/config.sh"

aws ec2 run-instances \
  --image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --instance-type "$INSTANCE_TYPE" \
  --key-name "$AWS_KEYPAIR_NAME" \
  --security-group-ids "$AWS_SECURITY_GROUP" \
  --monitoring Enabled=true \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$BUILDER_NAME}]" \
  --query "Instances[0].InstanceId" \
  --output text > "$DIR/instance.id"

echo "New instance with id $(cat "$DIR/instance.id")."

aws ec2 wait instance-running --instance-ids "$(cat "$DIR/instance.id")"
aws ec2 wait instance-status-ok --instance-ids "$(cat "$DIR/instance.id")"
echo "Builder instance $(cat "$DIR/instance.id") is running and passed status checks."

aws ec2 describe-instances \
  --instance-ids "$(cat "$DIR/instance.id")" \
  --query "Reservations[0].Instances[0].PublicDnsName" \
  --output text > "$DIR/instance.dns"

echo "Builder instance DNS: $(cat "$DIR/instance.dns")"

while ! nc -z -w 2 "$(cat "$DIR/instance.dns")" 22; do
  echo "Waiting for $(cat "$DIR/instance.dns"):22 (SSH)..."
  sleep 1
done

echo "SSH is ready."
