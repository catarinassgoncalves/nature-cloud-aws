#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$DIR/config.sh"

if [ ! -f "$DIR/image.id" ]; then
  echo "image.id not found."
  exit 1
fi

aws ec2 deregister-image \
  --delete-associated-snapshots \
  --image-id "$(cat "$DIR/image.id")"
