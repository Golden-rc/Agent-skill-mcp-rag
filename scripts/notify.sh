#!/usr/bin/env bash
set -euo pipefail

TITLE="${1:-OpenCode}"
MESSAGE="${2:-任务已完成}"

if command -v terminal-notifier >/dev/null 2>&1; then
  terminal-notifier -title "$TITLE" -message "$MESSAGE" -sound default
  exit 0
fi

osascript -e "display notification \"${MESSAGE//\"/\\\"}\" with title \"${TITLE//\"/\\\"}\" sound name \"Glass\""
