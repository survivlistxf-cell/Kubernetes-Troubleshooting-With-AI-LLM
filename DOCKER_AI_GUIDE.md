# AI stack (Ollama + AI Server) – Docker Compose

This repo has 2 separate compose stacks:

- `docker-compose.yml` → **client stack** (postgres + backend + frontend)
- `docker-compose-ai.yml` → **AI stack** (ollama + ai-server)

Use this separation when client and AI run on different machines.

## Prerequisites

- Docker Desktop
- Enough disk space for model weights (several GB)

## Start AI stack

From repo root:

```powershell
docker compose -f docker-compose-ai.yml up -d --build
```

### Optional: pull model weights (recommended)

This makes the first request fast.

```powershell
docker compose -f docker-compose-ai.yml run --rm ollama-init
```

## Verify

### 1) Ollama is reachable

```powershell
Invoke-RestMethod http://localhost:11434/api/tags
```

### 2) AI server is reachable

```powershell
Invoke-RestMethod http://localhost:8090/health
```

### 3) AI server chat endpoint

```powershell
$kdiag = '{"protocol_version":"kdiag/1.0","message":{"role":"user","text":"Hello from docker"}}'
Invoke-RestMethod -Method Post -Uri "http://localhost:8090/v1/chat" -ContentType "application/json" -Body $kdiag
```

## Connect the client backend to the AI server

If the **client backend** runs on another machine, set:

- `ai.server.base-url=http://<AI_HOST>:8090`

If you run backend in Docker on the same machine, set it to the host IP / DNS that Docker can reach.

## Notes

- Inside Docker Compose network, services refer to each other by service name:
  - AI server uses `http://ollama:11434`
- On Windows host, you reach exposed ports via `http://localhost:<port>`.
