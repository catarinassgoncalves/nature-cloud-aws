#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$DIR/config.sh"

REGION="${AWS_DEFAULT_REGION:-us-east-1}"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"

LAMBDA_ROLE_NAME="${LAMBDA_ROLE_NAME:-CNV-NatureCloud-LambdaRole}"
LAMBDA_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${LAMBDA_ROLE_NAME}"

FRACTALS_FUNCTION_NAME="${FRACTALS_FUNCTION_NAME:-CNV-NatureCloud-Fractals}"
GRAYSCOTT_FUNCTION_NAME="${GRAYSCOTT_FUNCTION_NAME:-CNV-NatureCloud-GrayScott}"
DNA_FUNCTION_NAME="${DNA_FUNCTION_NAME:-CNV-NatureCloud-Dna}"

LAMBDA_MEMORY_MB="${LAMBDA_MEMORY_MB:-2048}"
LAMBDA_TIMEOUT_SECONDS="${LAMBDA_TIMEOUT_SECONDS:-900}"

PROJECT_DIR="${LOCAL_PROJECT_DIR:-/home/vagrant/cnv-shared/cnvproject}"

FRACTALS_JAR="$PROJECT_DIR/fractals/target/fractals-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
GRAYSCOTT_JAR="$PROJECT_DIR/grayscott/target/grayscott-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
DNA_JAR="$PROJECT_DIR/dna/target/dna-1.0.0-SNAPSHOT-jar-with-dependencies.jar"

echo "Building project before Lambda deployment..."
cd "$PROJECT_DIR"
mvn clean package

if [ ! -f "$FRACTALS_JAR" ]; then
  echo "Missing jar: $FRACTALS_JAR"
  exit 1
fi

if [ ! -f "$GRAYSCOTT_JAR" ]; then
  echo "Missing jar: $GRAYSCOTT_JAR"
  exit 1
fi

if [ ! -f "$DNA_JAR" ]; then
  echo "Missing jar: $DNA_JAR"
  exit 1
fi

deploy_function() {
  local function_name="$1"
  local handler="$2"
  local jar_path="$3"

  echo "Deploying Lambda function: $function_name"

  if aws lambda get-function --function-name "$function_name" > /dev/null 2>&1; then
    echo "Function exists. Updating code..."
    aws lambda update-function-code \
      --function-name "$function_name" \
      --zip-file "fileb://$jar_path" > /dev/null

    echo "Waiting for code update..."
    aws lambda wait function-updated \
      --function-name "$function_name"

    echo "Updating configuration..."
    aws lambda update-function-configuration \
      --function-name "$function_name" \
      --runtime java11 \
      --handler "$handler" \
      --memory-size "$LAMBDA_MEMORY_MB" \
      --timeout "$LAMBDA_TIMEOUT_SECONDS" \
      --role "$LAMBDA_ROLE_ARN" > /dev/null

    aws lambda wait function-updated \
      --function-name "$function_name"
  else
    echo "Function does not exist. Creating..."
    aws lambda create-function \
      --function-name "$function_name" \
      --runtime java11 \
      --handler "$handler" \
      --memory-size "$LAMBDA_MEMORY_MB" \
      --timeout "$LAMBDA_TIMEOUT_SECONDS" \
      --role "$LAMBDA_ROLE_ARN" \
      --zip-file "fileb://$jar_path" > /dev/null

    aws lambda wait function-active \
      --function-name "$function_name"
  fi

  echo "Lambda ready: $function_name"
}

deploy_function \
  "$FRACTALS_FUNCTION_NAME" \
  "pt.ulisboa.tecnico.cnv.fractals.FractalsHandler::handleRequest" \
  "$FRACTALS_JAR"

deploy_function \
  "$GRAYSCOTT_FUNCTION_NAME" \
  "pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler::handleRequest" \
  "$GRAYSCOTT_JAR"

deploy_function \
  "$DNA_FUNCTION_NAME" \
  "pt.ulisboa.tecnico.cnv.dna.DnaHandler::handleRequest" \
  "$DNA_JAR"

echo "Lambda deployment complete."
echo "Functions:"
echo "  $FRACTALS_FUNCTION_NAME"
echo "  $GRAYSCOTT_FUNCTION_NAME"
echo "  $DNA_FUNCTION_NAME"