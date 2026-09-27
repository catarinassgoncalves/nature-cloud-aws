#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$DIR/config.sh"

rm -f "$DIR/instance.id" "$DIR/instance.dns" "$DIR/image.id"

"$DIR/launch-vm.sh"
"$DIR/install-vm.sh"
"$DIR/test-vm.sh"

IMAGE_NAME="${AMI_NAME}-$(date +%Y%m%d-%H%M%S)"

aws ec2 create-image \
  --instance-id "$(cat "$DIR/instance.id")" \
  --name "$IMAGE_NAME" \
  --query "ImageId" \
  --output text > "$DIR/image.id"

echo "Created image $(cat "$DIR/image.id") with name $IMAGE_NAME"

echo "Waiting for AMI to become available..."
aws ec2 wait image-available --image-ids "$(cat "$DIR/image.id")"
echo "AMI is available."

echo "Terminating builder instance..."
aws ec2 terminate-instances --instance-ids "$(cat "$DIR/instance.id")" > /dev/null
aws ec2 wait instance-terminated --instance-ids "$(cat "$DIR/instance.id")"
echo "Builder instance terminated."
