# AI Server (kdiag/1.0)

Spring Boot server intended to host an AI (LLM) Kubernetes troubleshooting assistant.

## What it does (MVP)

- Accepts chat requests using a **kdiag/1.0**-style JSON payload.
- Returns an assistant reply + optional `actions_requested` the client can execute (kubectl collectors).
- Exposes health endpoints for deployment.

## Endpoints (planned)

- `POST /v1/chat` — main entrypoint (kdiag/1.0 request)
- `GET /health` — liveness
- `GET /ready` — readiness

## Next steps

- Implement `POST /v1/chat` (validate payload + stub AI reply)
- Add persistence (Postgres) for conversations/artifacts
- Add RAG on Kubernetes docs ("Troubleshooting Applications")
- Add auth (JWT/API key) + rate limiting
