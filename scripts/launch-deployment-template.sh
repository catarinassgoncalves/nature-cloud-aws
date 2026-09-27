#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$DIR/config.sh"

WORKER_INSTANCE_PROFILE_NAME="${WORKER_INSTANCE_PROFILE_NAME:-CNV-NatureCloud-WorkerInstanceProfile}"

if [ ! -f "$DIR/image.id" ]; then
  echo "image.id not found. Run create-image.sh first."
  exit 1
fi

echo "Creating load balancer..."
aws elb create-load-balancer \
  --load-balancer-name "$ELB_NAME" \
  --listeners "Protocol=HTTP,LoadBalancerPort=80,InstanceProtocol=HTTP,InstancePort=$APP_PORT" \
  --availability-zones "$AWS_AVAILABILITY_ZONE"

echo "Configuring health check..."
aws elb configure-health-check \
  --load-balancer-name "$ELB_NAME" \
  --health-check "Target=HTTP:$APP_PORT$HEALTHCHECK_PATH,Interval=30,UnhealthyThreshold=2,HealthyThreshold=2,Timeout=5"

echo "Creating launch template..."
aws ec2 create-launch-template \
  --launch-template-name "$LAUNCH_TEMPLATE_NAME" \
  --version-description "v1" \
  --launch-template-data "{
    \"ImageId\": \"$(cat "$DIR/image.id")\",
    \"InstanceType\": \"$INSTANCE_TYPE\",
    \"KeyName\": \"$AWS_KEYPAIR_NAME\",
    \"SecurityGroupIds\": [\"$AWS_SECURITY_GROUP\"],
    \"IamInstanceProfile\": {
      \"Name\": \"$WORKER_INSTANCE_PROFILE_NAME\"
    },
    \"MetadataOptions\": {
      \"HttpEndpoint\": \"enabled\",
      \"HttpTokens\": \"optional\"
    },
    \"Monitoring\": {\"Enabled\": true},
    \"TagSpecifications\": [
      {
        \"ResourceType\": \"instance\",
        \"Tags\": [{\"Key\": \"Name\", \"Value\": \"NatureCloud-Worker\"}]
      }
    ]
  }"

echo "Creating auto scaling group..."
aws autoscaling create-auto-scaling-group \
  --auto-scaling-group-name "$ASG_NAME" \
  --launch-template "LaunchTemplateName=$LAUNCH_TEMPLATE_NAME,Version=1" \
  --load-balancer-names "$ELB_NAME" \
  --availability-zones "$AWS_AVAILABILITY_ZONE" \
  --health-check-type ELB \
  --health-check-grace-period 120 \
  --min-size "$MIN_SIZE" \
  --max-size "$MAX_SIZE" \
  --desired-capacity "$DESIRED_CAPACITY"

echo "Deployment created."
echo "CloudWatch CPU metrics are still enabled for EC2 instances, but no CloudWatch scaling policies or alarm actions are created."
echo "The Java controller is responsible for programmatic autoscaling."
echo "Load balancer DNS:"
aws elb describe-load-balancers \
  --load-balancer-names "$ELB_NAME" \
  --query "LoadBalancerDescriptions[0].DNSName" \
  --output text