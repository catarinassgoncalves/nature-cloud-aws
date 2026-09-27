#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$DIR/config.sh"

WORKER_ROLE_NAME="${WORKER_ROLE_NAME:-CNV-NatureCloud-WorkerRole}"
WORKER_INSTANCE_PROFILE_NAME="${WORKER_INSTANCE_PROFILE_NAME:-CNV-NatureCloud-WorkerInstanceProfile}"

CONTROLLER_ROLE_NAME="${CONTROLLER_ROLE_NAME:-CNV-NatureCloud-ControllerRole}"
CONTROLLER_INSTANCE_PROFILE_NAME="${CONTROLLER_INSTANCE_PROFILE_NAME:-CNV-NatureCloud-ControllerInstanceProfile}"

LAMBDA_ROLE_NAME="${LAMBDA_ROLE_NAME:-CNV-NatureCloud-LambdaRole}"

METRICS_TABLE_NAME="${METRICS_TABLE_NAME:-NatureCloudMetrics}"

FRACTALS_FUNCTION_NAME="${FRACTALS_FUNCTION_NAME:-CNV-NatureCloud-Fractals}"
GRAYSCOTT_FUNCTION_NAME="${GRAYSCOTT_FUNCTION_NAME:-CNV-NatureCloud-GrayScott}"
DNA_FUNCTION_NAME="${DNA_FUNCTION_NAME:-CNV-NatureCloud-Dna}"

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
REGION="${AWS_DEFAULT_REGION:-us-east-1}"

METRICS_TABLE_ARN="arn:aws:dynamodb:${REGION}:${ACCOUNT_ID}:table/${METRICS_TABLE_NAME}"
FRACTALS_FUNCTION_ARN="arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${FRACTALS_FUNCTION_NAME}"
GRAYSCOTT_FUNCTION_ARN="arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${GRAYSCOTT_FUNCTION_NAME}"
DNA_FUNCTION_ARN="arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:${DNA_FUNCTION_NAME}"

echo "Using account: $ACCOUNT_ID"
echo "Using region: $REGION"
echo "Using metrics table: $METRICS_TABLE_NAME"

cat > /tmp/cnv-ec2-trust-policy.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

cat > /tmp/cnv-lambda-trust-policy.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "lambda.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

echo "Creating/updating worker role..."
aws iam create-role \
  --role-name "$WORKER_ROLE_NAME" \
  --assume-role-policy-document file:///tmp/cnv-ec2-trust-policy.json \
  2>/dev/null || true

cat > /tmp/cnv-worker-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:PutItem",
        "dynamodb:DescribeTable"
      ],
      "Resource": "$METRICS_TABLE_ARN"
    }
  ]
}
EOF

aws iam put-role-policy \
  --role-name "$WORKER_ROLE_NAME" \
  --policy-name CNV-NatureCloud-WorkerPolicy \
  --policy-document file:///tmp/cnv-worker-policy.json

echo "Creating worker instance profile..."
aws iam create-instance-profile \
  --instance-profile-name "$WORKER_INSTANCE_PROFILE_NAME" \
  2>/dev/null || true

echo "Adding worker role to worker instance profile..."
aws iam add-role-to-instance-profile \
  --instance-profile-name "$WORKER_INSTANCE_PROFILE_NAME" \
  --role-name "$WORKER_ROLE_NAME" \
  2>/dev/null || true

echo "Creating/updating controller role..."
aws iam create-role \
  --role-name "$CONTROLLER_ROLE_NAME" \
  --assume-role-policy-document file:///tmp/cnv-ec2-trust-policy.json \
  2>/dev/null || true

cat > /tmp/cnv-controller-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "autoscaling:DescribeAutoScalingGroups",
        "autoscaling:SetDesiredCapacity",
        "autoscaling:TerminateInstanceInAutoScalingGroup",
        "ec2:DescribeInstances",
        "ec2:DescribeInstanceStatus",
        "cloudwatch:GetMetricData",
        "cloudwatch:GetMetricStatistics",
        "cloudwatch:ListMetrics"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:Query",
        "dynamodb:DescribeTable"
      ],
      "Resource": "$METRICS_TABLE_ARN"
    },
    {
      "Effect": "Allow",
      "Action": [
        "lambda:InvokeFunction"
      ],
      "Resource": [
        "$FRACTALS_FUNCTION_ARN",
        "$GRAYSCOTT_FUNCTION_ARN",
        "$DNA_FUNCTION_ARN"
      ]
    }
  ]
}
EOF

aws iam put-role-policy \
  --role-name "$CONTROLLER_ROLE_NAME" \
  --policy-name CNV-NatureCloud-ControllerPolicy \
  --policy-document file:///tmp/cnv-controller-policy.json

echo "Creating controller instance profile..."
aws iam create-instance-profile \
  --instance-profile-name "$CONTROLLER_INSTANCE_PROFILE_NAME" \
  2>/dev/null || true

echo "Adding controller role to controller instance profile..."
aws iam add-role-to-instance-profile \
  --instance-profile-name "$CONTROLLER_INSTANCE_PROFILE_NAME" \
  --role-name "$CONTROLLER_ROLE_NAME" \
  2>/dev/null || true

echo "Creating/updating Lambda role..."
aws iam create-role \
  --role-name "$LAMBDA_ROLE_NAME" \
  --assume-role-policy-document file:///tmp/cnv-lambda-trust-policy.json \
  2>/dev/null || true

cat > /tmp/cnv-lambda-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "arn:aws:logs:${REGION}:${ACCOUNT_ID}:*"
    }
  ]
}
EOF

aws iam put-role-policy \
  --role-name "$LAMBDA_ROLE_NAME" \
  --policy-name CNV-NatureCloud-LambdaPolicy \
  --policy-document file:///tmp/cnv-lambda-policy.json

echo "IAM setup complete."
echo "Worker instance profile: $WORKER_INSTANCE_PROFILE_NAME"
echo "Controller instance profile: $CONTROLLER_INSTANCE_PROFILE_NAME"
echo "Lambda role: $LAMBDA_ROLE_NAME"
echo "Lambda functions allowed for controller invoke:"
echo "  $FRACTALS_FUNCTION_NAME"
echo "  $GRAYSCOTT_FUNCTION_NAME"
echo "  $DNA_FUNCTION_NAME"