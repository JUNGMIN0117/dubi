#!/usr/bin/env sh
set -eu

if [ "$#" -lt 1 ]; then
  echo "usage: $0 <job.py> [arguments...]" >&2
  exit 1
fi

exec spark-submit "$@"
