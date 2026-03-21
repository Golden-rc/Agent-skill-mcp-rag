#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "Usage: scripts/run-with-notify.sh <command...>"
  exit 1
fi

COMMAND="$*"
START_TS=$(date +%s)

set +e
bash -lc "$COMMAND"
STATUS=$?
set -e

END_TS=$(date +%s)
ELAPSED=$((END_TS - START_TS))

if [ "$STATUS" -eq 0 ]; then
  "$(dirname "$0")/notify.sh" "OpenCode" "任务完成: ${COMMAND} (${ELAPSED}s)"
else
  "$(dirname "$0")/notify.sh" "OpenCode" "任务失败: ${COMMAND} (${ELAPSED}s)"
fi

exit "$STATUS"
