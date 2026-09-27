#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$DIR/config.sh"

FRACTALS_FUNCTION_NAME="${FRACTALS_FUNCTION_NAME:-CNV-NatureCloud-Fractals}"
GRAYSCOTT_FUNCTION_NAME="${GRAYSCOTT_FUNCTION_NAME:-CNV-NatureCloud-GrayScott}"
DNA_FUNCTION_NAME="${DNA_FUNCTION_NAME:-CNV-NatureCloud-Dna}"

delete_function() {
  local function_name="$1"

  echo "Deleting Lambda function if it exists: $function_name"

  aws lambda delete-function \
    --function-name "$function_name" \
    2>/dev/null || true
}

delete_function "$FRACTALS_FUNCTION_NAME"
delete_function "$GRAYSCOTT_FUNCTION_NAME"
delete_function "$DNA_FUNCTION_NAME"

echo "Lambda functions deleted."