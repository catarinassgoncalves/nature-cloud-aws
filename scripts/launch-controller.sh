#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$DIR/config.sh"

CONTROLLER_NAME="${CONTROLLER_NAME:-NatureCloud-Controller}"
CONTROLLER_INSTANCE_TYPE="${CONTROLLER_INSTANCE_TYPE:-$INSTANCE_TYPE}"
CONTROLLER_PORT="${CONTROLLER_PORT:-8080}"
CONTROLLER_INSTANCE_PROFILE_NAME="${CONTROLLER_INSTANCE_PROFILE_NAME:-CNV-NatureCloud-ControllerInstanceProfile}"
CONTROLLER_SERVICE_NAME="${CONTROLLER_SERVICE_NAME:-naturecloud-controller}"

CONTROLLER_LB_PORT="${CONTROLLER_LB_PORT:-8080}"
CONTROLLER_WORKER_ADDRESS_MODE="${CONTROLLER_WORKER_ADDRESS_MODE:-private}"
CONTROLLER_DISCOVERED_WORKER_PORT="${CONTROLLER_DISCOVERED_WORKER_PORT:-8000}"
CONTROLLER_DISCOVERY_INTERVAL_SECONDS="${CONTROLLER_DISCOVERY_INTERVAL_SECONDS:-15}"

CONTROLLER_AS_ENABLED="${CONTROLLER_AS_ENABLED:-true}"
CNV_AS_INTERVAL_SECONDS="${CNV_AS_INTERVAL_SECONDS:-15}"
CNV_AS_SCALE_OUT_CONSECUTIVE="${CNV_AS_SCALE_OUT_CONSECUTIVE:-2}"
CNV_AS_SCALE_IN_CONSECUTIVE="${CNV_AS_SCALE_IN_CONSECUTIVE:-4}"
CNV_AS_COOLDOWN_SECONDS="${CNV_AS_COOLDOWN_SECONDS:-120}"
CNV_AS_HIGH_AVG_LOAD="${CNV_AS_HIGH_AVG_LOAD:-2500000000}"
CNV_AS_LOW_AVG_LOAD="${CNV_AS_LOW_AVG_LOAD:-250000000}"

CNV_DYNAMIC_COST_MODEL_ENABLED="${CNV_DYNAMIC_COST_MODEL_ENABLED:-true}"
CNV_COST_HISTORY_LIMIT="${CNV_COST_HISTORY_LIMIT:-200}"
CNV_COST_REFRESH_SECONDS="${CNV_COST_REFRESH_SECONDS:-30}"
CNV_METRICS_TABLE="${CNV_METRICS_TABLE:-NatureCloudMetrics}"

CNV_LAMBDA_ENABLED="${CNV_LAMBDA_ENABLED:-true}"
CNV_LAMBDA_OVERFLOW_THRESHOLD_SECONDS="${CNV_LAMBDA_OVERFLOW_THRESHOLD_SECONDS:-30}"
CNV_LAMBDA_OVERFLOW_ONLY_WHEN_EC2_BUSY="${CNV_LAMBDA_OVERFLOW_ONLY_WHEN_EC2_BUSY:-true}"
CNV_LAMBDA_MAX_ESTIMATED_INSTRUCTIONS="${CNV_LAMBDA_MAX_ESTIMATED_INSTRUCTIONS:-60000000000}"
CNV_LAMBDA_FRACTALS_FUNCTION="${CNV_LAMBDA_FRACTALS_FUNCTION:-CNV-NatureCloud-Fractals}"
CNV_LAMBDA_GRAYSCOTT_FUNCTION="${CNV_LAMBDA_GRAYSCOTT_FUNCTION:-CNV-NatureCloud-GrayScott}"
CNV_LAMBDA_DNA_FUNCTION="${CNV_LAMBDA_DNA_FUNCTION:-CNV-NatureCloud-Dna}"

if [ ! -f "$DIR/image.id" ]; then
  echo "image.id not found. Run create-image.sh first."
  exit 1
fi

echo "Opening controller port $CONTROLLER_PORT in security group if needed..."
aws ec2 authorize-security-group-ingress \
  --group-id "$AWS_SECURITY_GROUP" \
  --protocol tcp \
  --port "$CONTROLLER_PORT" \
  --cidr 0.0.0.0/0 2>/dev/null || true

echo "Creating controller user-data..."
cat > /tmp/cnv-controller-user-data.sh <<EOF
#!/bin/bash
set -e

mkdir -p /home/ec2-user/cnvproject/logs

systemctl stop naturecloud.service || true
systemctl disable naturecloud.service || true

cat > /home/ec2-user/cnvproject/run-controller.sh <<'EOS'
#!/bin/bash
set -e

cd /home/ec2-user/cnvproject
mkdir -p /home/ec2-user/cnvproject/logs

export AWS_REGION="${AWS_DEFAULT_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

export CNV_WORKER_DISCOVERY=asg
export CNV_ASG_NAME="${ASG_NAME}"
export CNV_WORKER_ADDRESS_MODE="${CONTROLLER_WORKER_ADDRESS_MODE}"
export CNV_DISCOVERED_WORKER_PORT="${CONTROLLER_DISCOVERED_WORKER_PORT}"
export CNV_WORKER_DISCOVERY_INTERVAL_SECONDS="${CONTROLLER_DISCOVERY_INTERVAL_SECONDS}"
export CNV_LB_PORT="${CONTROLLER_LB_PORT}"

export CNV_DYNAMIC_COST_MODEL_ENABLED="${CNV_DYNAMIC_COST_MODEL_ENABLED}"
export CNV_COST_HISTORY_LIMIT="${CNV_COST_HISTORY_LIMIT}"
export CNV_COST_REFRESH_SECONDS="${CNV_COST_REFRESH_SECONDS}"
export CNV_METRICS_TABLE="${CNV_METRICS_TABLE}"

export CNV_AS_ENABLED="${CONTROLLER_AS_ENABLED}"
export CNV_AS_MIN_WORKERS="${MIN_SIZE:-1}"
export CNV_AS_MAX_WORKERS="${MAX_SIZE:-3}"
export CNV_AS_INTERVAL_SECONDS="${CNV_AS_INTERVAL_SECONDS}"
export CNV_AS_SCALE_OUT_CONSECUTIVE="${CNV_AS_SCALE_OUT_CONSECUTIVE}"
export CNV_AS_SCALE_IN_CONSECUTIVE="${CNV_AS_SCALE_IN_CONSECUTIVE}"
export CNV_AS_COOLDOWN_SECONDS="${CNV_AS_COOLDOWN_SECONDS}"
export CNV_AS_HIGH_AVG_LOAD="${CNV_AS_HIGH_AVG_LOAD}"
export CNV_AS_LOW_AVG_LOAD="${CNV_AS_LOW_AVG_LOAD}"

export CNV_LAMBDA_ENABLED="${CNV_LAMBDA_ENABLED}"
export CNV_LAMBDA_OVERFLOW_THRESHOLD_SECONDS="${CNV_LAMBDA_OVERFLOW_THRESHOLD_SECONDS}"
export CNV_LAMBDA_OVERFLOW_ONLY_WHEN_EC2_BUSY="${CNV_LAMBDA_OVERFLOW_ONLY_WHEN_EC2_BUSY}"
export CNV_LAMBDA_MAX_ESTIMATED_INSTRUCTIONS="${CNV_LAMBDA_MAX_ESTIMATED_INSTRUCTIONS}"
export CNV_LAMBDA_FRACTALS_FUNCTION="${CNV_LAMBDA_FRACTALS_FUNCTION}"
export CNV_LAMBDA_GRAYSCOTT_FUNCTION="${CNV_LAMBDA_GRAYSCOTT_FUNCTION}"
export CNV_LAMBDA_DNA_FUNCTION="${CNV_LAMBDA_DNA_FUNCTION}"

echo "Starting Nature@Cloud controller"
echo "CNV_ASG_NAME=\$CNV_ASG_NAME"
echo "CNV_WORKER_ADDRESS_MODE=\$CNV_WORKER_ADDRESS_MODE"
echo "CNV_DISCOVERED_WORKER_PORT=\$CNV_DISCOVERED_WORKER_PORT"
echo "CNV_LB_PORT=\$CNV_LB_PORT"
echo "CNV_DYNAMIC_COST_MODEL_ENABLED=\$CNV_DYNAMIC_COST_MODEL_ENABLED"
echo "CNV_METRICS_TABLE=\$CNV_METRICS_TABLE"
echo "CNV_AS_ENABLED=\$CNV_AS_ENABLED"
echo "CNV_AS_MIN_WORKERS=\$CNV_AS_MIN_WORKERS"
echo "CNV_AS_MAX_WORKERS=\$CNV_AS_MAX_WORKERS"
echo "CNV_AS_INTERVAL_SECONDS=\$CNV_AS_INTERVAL_SECONDS"
echo "CNV_AS_HIGH_AVG_LOAD=\$CNV_AS_HIGH_AVG_LOAD"
echo "CNV_AS_LOW_AVG_LOAD=\$CNV_AS_LOW_AVG_LOAD"
echo "CNV_LAMBDA_ENABLED=\$CNV_LAMBDA_ENABLED"
echo "CNV_LAMBDA_OVERFLOW_THRESHOLD_SECONDS=\$CNV_LAMBDA_OVERFLOW_THRESHOLD_SECONDS"
echo "CNV_LAMBDA_OVERFLOW_ONLY_WHEN_EC2_BUSY=\$CNV_LAMBDA_OVERFLOW_ONLY_WHEN_EC2_BUSY"
echo "CNV_LAMBDA_MAX_ESTIMATED_INSTRUCTIONS=\$CNV_LAMBDA_MAX_ESTIMATED_INSTRUCTIONS"
echo "CNV_LAMBDA_FRACTALS_FUNCTION=\$CNV_LAMBDA_FRACTALS_FUNCTION"
echo "CNV_LAMBDA_GRAYSCOTT_FUNCTION=\$CNV_LAMBDA_GRAYSCOTT_FUNCTION"
echo "CNV_LAMBDA_DNA_FUNCTION=\$CNV_LAMBDA_DNA_FUNCTION"
echo "AWS_REGION=\$AWS_REGION"

exec /usr/bin/java \
-cp /home/ec2-user/cnvproject/webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
pt.ulisboa.tecnico.cnv.webserver.lb.LoadBalancerServer \
>> /home/ec2-user/cnvproject/logs/controller.log 2>&1
EOS

chmod +x /home/ec2-user/cnvproject/run-controller.sh
chown ec2-user:ec2-user /home/ec2-user/cnvproject/run-controller.sh

cat > /etc/systemd/system/${CONTROLLER_SERVICE_NAME}.service <<'EOS'
[Unit]
Description=Nature@Cloud Java Load Balancer and Auto Scaler controller
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user/cnvproject
ExecStart=/home/ec2-user/cnvproject/run-controller.sh
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOS

systemctl daemon-reload
systemctl enable ${CONTROLLER_SERVICE_NAME}.service
systemctl restart ${CONTROLLER_SERVICE_NAME}.service
EOF

echo "Launching controller instance..."
aws ec2 run-instances \
  --image-id "$(cat "$DIR/image.id")" \
  --instance-type "$CONTROLLER_INSTANCE_TYPE" \
  --key-name "$AWS_KEYPAIR_NAME" \
  --security-group-ids "$AWS_SECURITY_GROUP" \
  --iam-instance-profile Name="$CONTROLLER_INSTANCE_PROFILE_NAME" \
  --monitoring Enabled=true \
  --user-data file:///tmp/cnv-controller-user-data.sh \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$CONTROLLER_NAME}]" \
  --query "Instances[0].InstanceId" \
  --output text > "$DIR/controller.id"

echo "Controller instance id: $(cat "$DIR/controller.id")"

echo "Waiting for controller to run..."
aws ec2 wait instance-running --instance-ids "$(cat "$DIR/controller.id")"
aws ec2 wait instance-status-ok --instance-ids "$(cat "$DIR/controller.id")"

aws ec2 describe-instances \
  --instance-ids "$(cat "$DIR/controller.id")" \
  --query "Reservations[0].Instances[0].PublicIpAddress" \
  --output text > "$DIR/controller.ip"

aws ec2 describe-instances \
  --instance-ids "$(cat "$DIR/controller.id")" \
  --query "Reservations[0].Instances[0].PublicDnsName" \
  --output text > "$DIR/controller.dns"

echo "Controller public IP: $(cat "$DIR/controller.ip")"
echo "Controller public DNS: $(cat "$DIR/controller.dns")"

echo "Waiting for controller port $CONTROLLER_PORT..."
for i in $(seq 1 60); do
  if nc -z -w 2 "$(cat "$DIR/controller.ip")" "$CONTROLLER_PORT"; then
    echo "Controller is reachable on port $CONTROLLER_PORT."
    echo "Controller URL:"
    echo "http://$(cat "$DIR/controller.ip"):$CONTROLLER_PORT"
    exit 0
  fi

  echo "Waiting for $(cat "$DIR/controller.ip"):$CONTROLLER_PORT..."
  sleep 5
done

echo "Controller instance launched, but port $CONTROLLER_PORT was not reachable yet."
echo "SSH and check /home/ec2-user/cnvproject/logs/controller.log"
exit 1