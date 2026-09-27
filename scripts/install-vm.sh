#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$DIR/config.sh"

INSTANCE_DNS="$(cat "$DIR/instance.dns")"

if [ ! -d "$LOCAL_PROJECT_DIR" ]; then
  echo "LOCAL_PROJECT_DIR does not exist: $LOCAL_PROJECT_DIR"
  exit 1
fi

echo "Building project locally before packaging..."
cd "$LOCAL_PROJECT_DIR"
mvn clean package

echo "Creating deploy bundle..."
rm -f /tmp/cnvproject-deploy.tgz
tar czf /tmp/cnvproject-deploy.tgz \
  --exclude='.git' \
  --exclude='output' \
  --exclude='metrics-*.csv' \
  --exclude='.ipynb_checkpoints' \
  .

echo "Installing Java on remote VM..."
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@"$INSTANCE_DNS" \
  "sudo dnf -y update && sudo dnf -y install java-11-amazon-corretto-devel tar"

echo "Copying project to remote VM..."
scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" /tmp/cnvproject-deploy.tgz \
  ec2-user@"$INSTANCE_DNS":/tmp/cnvproject-deploy.tgz

echo "Installing project on remote VM..."
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@"$INSTANCE_DNS" '
  set -e
  rm -rf /home/ec2-user/cnvproject
  mkdir -p /home/ec2-user/cnvproject
  tar xzf /tmp/cnvproject-deploy.tgz -C /home/ec2-user/cnvproject
  mkdir -p /home/ec2-user/cnvproject/output
  mkdir -p /home/ec2-user/cnvproject/logs
  rm -f /home/ec2-user/cnvproject/metrics-*.csv

  cat > /home/ec2-user/cnvproject/run-worker.sh << "EOS"
#!/bin/bash
set -e

cd /home/ec2-user/cnvproject
mkdir -p /home/ec2-user/cnvproject/output
mkdir -p /home/ec2-user/cnvproject/logs

TOKEN="$(curl -s -X PUT http://169.254.169.254/latest/api/token \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 21600" || true)"

if [ -n "$TOKEN" ]; then
  INSTANCE_ID="$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" \
    http://169.254.169.254/latest/meta-data/instance-id || true)"
else
  INSTANCE_ID="$(curl -s http://169.254.169.254/latest/meta-data/instance-id || true)"
fi

if [ -z "$INSTANCE_ID" ]; then
  INSTANCE_ID="$(hostname)"
fi

export CNV_METRICS_DYNAMODB_ENABLED=true
export CNV_METRICS_TABLE="${CNV_METRICS_TABLE:-NatureCloudMetrics}"
export CNV_WORKER_ID="$INSTANCE_ID"
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

echo "Starting Nature@Cloud worker"
echo "CNV_WORKER_ID=$CNV_WORKER_ID"
echo "CNV_METRICS_DYNAMODB_ENABLED=$CNV_METRICS_DYNAMODB_ENABLED"
echo "CNV_METRICS_TABLE=$CNV_METRICS_TABLE"
echo "AWS_REGION=$AWS_REGION"
echo "AWS_DEFAULT_REGION=$AWS_DEFAULT_REGION"

exec /usr/bin/java \
-cp /home/ec2-user/cnvproject/webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
-Xbootclasspath/a:/home/ec2-user/cnvproject/javassist/target/JavassistWrapper-1.0-jar-with-dependencies.jar \
-javaagent:/home/ec2-user/cnvproject/webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar=RequestICount:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:/home/ec2-user/cnvproject/output \
pt.ulisboa.tecnico.cnv.webserver.WebServer \
>> /home/ec2-user/cnvproject/logs/webserver.log 2>&1
EOS

  chmod +x /home/ec2-user/cnvproject/run-worker.sh

  cat > /tmp/naturecloud.service << "EOS"
[Unit]
Description=Nature@Cloud instrumented worker
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user/cnvproject
ExecStart=/home/ec2-user/cnvproject/run-worker.sh
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOS

  sudo mv /tmp/naturecloud.service /etc/systemd/system/naturecloud.service
  sudo systemctl daemon-reload
  sudo systemctl enable naturecloud.service
  sudo systemctl restart naturecloud.service
  sleep 3
  sudo systemctl --no-pager --full status naturecloud.service || true
'