#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"

# 脚本独立运行时不会自动读取 .env，这里显式加载。
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "OPENAI_API_KEY 为空，请先 export 或写入 .env" >&2
  exit 1
fi

# 兼容复制粘贴带换行的 key，避免 401。
OPENAI_API_KEY="$(printf "%s" "${OPENAI_API_KEY}" | tr -d '\r\n')"
MODEL="${OPENAI_CHAT_MODEL:-GLM-4.6V-Flash}"

curl -sS -X POST \
  -H "Authorization: Bearer ${OPENAI_API_KEY}" \
  -H "Content-Type: application/json" \
  -H "User-Agent: agent-hub-curl/1.0" \
  -d "{\"model\":\"${MODEL}\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"1+1\"}]}" \
  "https://open.bigmodel.cn/api/paas/v4/chat/completions"
