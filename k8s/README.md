# Kubexplain Kubernetes deploy

This folder contains a self-contained Kubernetes base for the application in a dedicated namespace (`kubexplain`).

## What it deploys

- `postgres` (pgvector) for the backend + feedback embeddings
- `backend` on port `8080`
- `frontend` exposed as `NodePort 30082`
- `ai-server` on port `8090` (Spring Boot AI layer — chat on **gpt-oss** (external,
  OpenAI-compatible), embeddings on Ollama, retrieval on Elasticsearch)
- `elasticsearch` (single-node) on port `9200` for retrieval
- `ollama` on port `11434` — now **embeddings only** (`nomic-embed-text`, CPU)

## Architecture: chat vs embeddings

- **Chat / answer generation** → **gpt-oss** at
  `http://gpt-oss-120b.kubeflow-dcpd.kubeflow.int.stscloud.ro/v1/chat/completions`
  (model `openai/gpt-oss-120b`, OpenAI-compatible, Bearer JWT). The token lives in a
  k8s Secret (`gptoss-api`), never in git. See **Networking** below for why the URL
  keeps the hostname and uses `hostAliases`.
- **Embeddings** (case-based feedback retrieval) → stay on the Ollama VM
  (`nomic-embed-text`). This is the only thing Ollama still serves.
- **RAG** → Elasticsearch BM25 (no LLM / embeddings involved).

## The gpt-oss API token (Secret)

Create it in the cluster — **do not commit it**:

```bash
kubectl -n kubexplain create secret generic gptoss-api \
  --from-literal=api-key='<JWT>'
```

`ai-server` reads it via `LLM_CHAT_API_KEY` (`secretKeyRef` → `gptoss-api/api-key`).

## Networking: reaching gpt-oss

`gpt-oss-120b.kubeflow-dcpd.kubeflow.int.stscloud.ro` is an internal host that the
cluster DNS likely does **not** resolve (it was handed over as an `/etc/hosts` entry,
`193.35.0.10`). The `ai-server` Deployment therefore injects the mapping via
`hostAliases`. The hostname is kept in the URL (not a bare IP) because the
`193.35.0.10` Ingress routes on the **Host header** (typical kubeflow). Verify from
inside the cluster before deploy:

```bash
kubectl -n kubexplain run curltest --rm -it --image=curlimages/curl --restart=Never -- \
  curl -s -o /dev/null -w "%{http_code}\n" \
  --resolve gpt-oss-120b.kubeflow-dcpd.kubeflow.int.stscloud.ro:80:193.35.0.10 \
  -X POST http://gpt-oss-120b.kubeflow-dcpd.kubeflow.int.stscloud.ro/v1/chat/completions \
  -H "Authorization: Bearer <TOKEN>" -H "Content-Type: application/json" \
  -d '{"model":"openai/gpt-oss-120b","messages":[{"role":"user","content":"salut"}],"max_tokens":20}'
```

Expect `200`. `401` = wrong token/secret; `404` = wrong path
(`/v1/chat/completions` vs `/v1/completions`); `UnknownHost`/timeout = `hostAliases`
missing or wrong.

## The Ollama node (embeddings only)

Embeddings with `nomic-embed-text` are light and run on CPU, so Ollama **no longer
needs the heavy dedicated VM** it used for LLM inference. Its `resources` are reduced
accordingly. The Deployment still carries a `nodeSelector` (`workload=ollama`) +
`toleration` (`dedicated=ollama`) so placement is unchanged out of the box; if you
want Ollama back on the regular workers, drop both from `ollama-deployment.yaml` and
remove the node label/taint:

```bash
kubectl label node ollama-1 workload-
kubectl taint node ollama-1 dedicated-
```

(The model store is a `hostPath`, so moving nodes re-pulls the ~270 MB embedding model once.)

## Images

DockerHub images used by the manifests:

- `axiiiiiiii/proiect_licenta-backend:latest`
- `axiiiiiiii/proiect_licenta-frontend:latest`
- `axiiiiiiii/proiect_licenta-ai-server:latest`  (built from `./Server`)

`ollama/ollama`, `pgvector/pgvector` and `docker.elastic.co/.../elasticsearch`
are pulled from public registries — no build needed.

## Build and push

From the project root, after `docker login`:

```bash
docker build -t axiiiiiiii/proiect_licenta-backend:latest ./backend
docker build -t axiiiiiiii/proiect_licenta-frontend:latest ./frontend
docker build -t axiiiiiiii/proiect_licenta-ai-server:latest ./Server

docker push axiiiiiiii/proiect_licenta-backend:latest
docker push axiiiiiiii/proiect_licenta-frontend:latest
docker push axiiiiiiii/proiect_licenta-ai-server:latest
```

## Apply

Once the `gptoss-api` Secret exists and the images are pushed:

```bash
kubectl apply -k k8s
```

Watch the rollout — Ollama now pulls only `nomic-embed-text` (~270 MB) on first
start, so it becomes Ready quickly:

```bash
kubectl -n kubexplain get pods -o wide
kubectl -n kubexplain logs deploy/ollama -f          # follow embedding-model pull
kubectl -n kubexplain logs deploy/ai-server -f       # first chat → "LLM tokens: prompt=… eval=…"
```

## OpenStack access

Expose the frontend `NodePort` through your Octavia load balancer:

- listener port: `80`
- member port: `30082`
- backend members: the internal IPs of the Kubernetes worker nodes

The floating IP attached to `lb-2` can stay as-is. No Ingress controller required.

## Internal wiring (already set in the manifests)

- `backend` → `ai-server`: `AI_SERVER_BASE_URL=http://ai-server:8090`
- `ai-server` → gpt-oss (chat): `LLM_CHAT_BASE_URL=http://gpt-oss-120b.kubeflow-dcpd.kubeflow.int.stscloud.ro/v1`, token via `gptoss-api` Secret
- `ai-server` → Ollama (embeddings only): `OLLAMA_BASE_URL=http://ollama:11434`
- `ai-server` → Elasticsearch: `KDIAG_RETRIEVAL_ENGINE=elastic`, `KDIAG_ELASTIC_URI=http://elasticsearch:9200`
- `ai-server` / `backend` → Postgres: `jdbc:postgresql://postgres:5432/kubexplain_db`

## Notes

- **Elasticsearch** needs `vm.max_map_count >= 262144` on its host. The deployment
  sets it via a privileged init container. If your cluster blocks privileged pods,
  remove that init container and instead run on each worker:
  `sudo sysctl -w vm.max_map_count=262144` (persist in `/etc/sysctl.conf`).
- **Ollama model storage** uses a `hostPath` (`/var/lib/ollama`) on the dedicated
  node, so models survive pod restarts without re-downloading. Swap for a Cinder
  PVC if you prefer.
- **Elasticsearch / Ollama data** use `emptyDir` / `hostPath` respectively. For
  full persistence across node failures, move them to PVCs.
