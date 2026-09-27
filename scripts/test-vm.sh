#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$DIR/config.sh"

INSTANCE_ID="$(cat "$DIR/instance.id")"
INSTANCE_DNS="$(cat "$DIR/instance.dns")"

echo "Rebooting builder instance to verify service auto-start..."
aws ec2 reboot-instances --instance-ids "$INSTANCE_ID"
aws ec2 wait instance-status-ok --instance-ids "$INSTANCE_ID"

while ! nc -z -w 2 "$INSTANCE_DNS" "$APP_PORT"; do
  echo "Waiting for $INSTANCE_DNS:$APP_PORT..."
  sleep 1
done

echo "Testing root endpoint from outside..."
curl -fsS "http://$INSTANCE_DNS:$APP_PORT$HEALTHCHECK_PATH" > /dev/null

echo "Testing worker locally on the instance..."
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@"$INSTANCE_DNS" '
  set -e
  curl -fsS "http://127.0.0.1:8000/" > /dev/null
  curl -fsS "http://127.0.0.1:8000/grayscott?size=32&maxIterations=10&f=0.022&k=0.051&stopOnExtinction=false&seedMode=center" > /dev/null
  test -f /home/ec2-user/cnvproject/logs/webserver.log
  test -f /home/ec2-user/cnvproject/metrics-grayscott.csv
  echo "Service is running and metrics file exists."
  tail -n 10 /home/ec2-user/cnvproject/logs/webserver.log || true
'
