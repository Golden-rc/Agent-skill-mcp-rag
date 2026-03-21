# Agent Hub (Java)

Java multi-agent demo project for internship preparation, covering:

- Multi-agent orchestration (`/chat`)
- RAG with PostgreSQL + pgvector (`/rag/ingest`)
- MCP-style skill server (`mcp-skill-server`)
- Redis conversation memory
- Dual model providers: local Ollama + OpenAI-compatible API

## 1) Prerequisites

- Docker + Docker Compose
- Java 21
- Maven 3.9+

On macOS (Homebrew):

```bash
brew install openjdk@21 maven
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

## 2) Start infrastructure

```bash
cp .env .env
docker compose up -d
```

If you use cloud API (no local Ollama), start only Postgres + Redis:

```bash
cp .env .env
docker compose -f docker-compose.api.yml up -d
```

Pull local models (first run):

```bash
ollama pull qwen3:4b
ollama pull nomic-embed-text
```

## 3) Start MCP skill server

```bash
cd mcp-skill-server
mvn spring-boot:run
```

## 4) Start main app

```bash
cd ..
mvn spring-boot:run
```

## 5) Verify APIs

Health:

```bash
curl http://localhost:8080/health
```

Ingest RAG text:

```bash
curl -X POST http://localhost:8080/rag/ingest \
  -H 'Content-Type: application/json' \
  -d '{
    "source":"internship-guide",
    "text":"AI工程师实习重点包括Agent架构、RAG系统、工具链封装、记忆管理和安全合规。"
  }'
```

Ingest file document:

```bash
curl -X POST http://localhost:8080/rag/ingest/file \
  -F "file=@/path/to/your/doc.pdf" \
  -F "source=custom-source"
```

Chat with auto provider (OpenAI if key exists, else Ollama):

```bash
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "sessionId":"demo-1",
    "provider":"auto",
    "mode":"auto",
    "message":"请基于知识库给我一个实习项目计划，并总结成待办"
  }'
```

`mode` options:
- `auto`: short/simple question uses direct answer, others use RAG
- `direct`: strict direct answer (skip history/RAG/tools)
- `rag`: force knowledge-enhanced answer

Admin APIs for operations page:

```bash
# list rag chunks
curl "http://localhost:8080/admin/rag/chunks?limit=20"

# list sessions
curl "http://localhost:8080/admin/sessions"
```

## 6) API config (BigModel)

If you want cloud model routing via BigModel, set in `.env` (or shell env):

```bash
OPENAI_BASE_URL=https://open.bigmodel.cn/api/paas/v4
OPENAI_API_KEY=your_bigmodel_api_key
OPENAI_CHAT_MODEL=glm-4-flash
```

The app loads `.env` automatically at startup.

For BigModel `OPENAI_API_KEY` in `id.secret` format, the app will auto-generate the bearer token internally.

Any OpenAI-compatible gateway can be used by changing `OPENAI_BASE_URL`.

Use `provider=openai` (or `provider=auto` with non-empty `OPENAI_API_KEY`) when calling `/chat`.

## RAG embedding config (BigModel/OpenAI-compatible)

For BigModel gateway (`https://open.bigmodel.cn/api/paas/v4`), use:

```bash
RAG_EMBED_PROVIDER=openai
RAG_EMBED_MODEL=embedding-2
RAG_EMBED_DIM=1024
RAG_CHUNK_SIZE=700
RAG_CHUNK_OVERLAP=120
RAG_CHUNK_MIN_SIZE=120
RAG_RECALL_TOPK=20
RAG_FINAL_TOPK=5
RAG_MIN_SCORE=0.35
RAG_CITATION_MAX_CHARS=50
```

`embedding-3` is 2048-dim, but pgvector `ivfflat` index cannot index vectors above 2000 dimensions.
If you need `embedding-3`, switch index strategy (e.g. HNSW) first.

When switching embedding model/dimension, rebuild RAG vectors:

```sql
TRUNCATE TABLE rag_chunks;
```

Then re-run `/rag/ingest` or `/rag/ingest/file` to re-embed all documents.

## Troubleshooting

- If Ollama returns memory errors for `qwen3:4b`, use a smaller model:

```bash
docker exec agenthub-ollama ollama pull qwen2.5:1.5b
```

- Then run the IDEA config `All Services (Low-Memory)`.

## Project structure

- `src/main/java/com/eureka/agenthub` main Spring Boot app
- `mcp-skill-server` standalone skill server
- `docker-compose.yml` local infra
- `src/main/resources/static/chat.html` chat page
- `src/main/resources/static/ingest.html` rag ingest page (text + file)
- `src/main/resources/static/rag.html` rag management page
- `src/main/resources/static/sessions.html` session management page

## MCP protocol support

This project now follows standard MCP handshake + tools flow:

- `initialize`
- `notifications/initialized`
- `tools/list`
- `tools/call`
- `ping`

`tools/call` responses use MCP content blocks (`result.content: [{type:"text", text:"..."}]`).

## MCP tool export

- Local tool list: `GET /admin/mcp/tools`
- Export manifest: `GET /admin/mcp/export`

## OpenCode completion reminder (macOS)

- `scripts/notify.sh`: send a local desktop notification
- `scripts/run-with-notify.sh`: run any command, then notify success/failure

Examples:

```bash
scripts/notify.sh "OpenCode" "任务已完成"
scripts/run-with-notify.sh "mvn test"
scripts/run-with-notify.sh "docker compose -f docker-compose.api.yml up -d"
```

If `terminal-notifier` is not installed, the script falls back to `osascript`.
