# Kubexplain Kubernetes deploy

This folder contains a self-contained Kubernetes base for the application in a dedicated namespace (`kubexplain`).

## What it deploys

- `postgres` (pgvector) for the backend + feedback embeddings
- `backend` on port `8080`
- `frontend` exposed as `NodePort 30082`
- `ai-server` on port `8090` (Spring Boot AI layer — talks to Ollama + Elasticsearch)
- `elasticsearch` (single-node) on port `9200` for retrieval
- `ollama` on port `11434`, **pinned to a dedicated VM** (CPU LLM inference)

## Prerequisites: the dedicated Ollama node

Ollama (llama3.1 8B) does not fit on the worker nodes, so it runs on its own VM.

1. Create the VM in OpenStack: **`c1a.2xlarge` (8 vCPU / 16 GB RAM) + 40 GB volume**.
   This needs the RAM quota raised from 50 GB to ~64 GB (vCPU and the rest fit as-is).
2. Join it to the cluster as a worker node (e.g. `ollama-1`).
3. Label it and taint it so **only** Ollama lands there and nothing else does:

```bash
kubectl label node ollama-1 workload=ollama
kubectl taint node ollama-1 dedicated=ollama:NoSchedule
```

The `ollama` Deployment has a matching `nodeSelector` + `toleration`; every other
workload has neither, so it stays on the regular workers.

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

Once the Ollama node is labelled/tainted and the images are pushed:

```bash
kubectl apply -k k8s
```

Watch the rollout — Ollama pulls ~5 GB of models on first start, so its pod
takes a few minutes to become Ready:

```bash
kubectl -n kubexplain get pods -o wide
kubectl -n kubexplain logs deploy/ollama -f          # follow model pull
```

Confirm placement: the `ollama` pod must be on `ollama-1`; everything else on the workers.

## OpenStack access

Expose the frontend `NodePort` through your Octavia load balancer:

- listener port: `80`
- member port: `30082`
- backend members: the internal IPs of the Kubernetes worker nodes

The floating IP attached to `lb-2` can stay as-is. No Ingress controller required.

## Internal wiring (already set in the manifests)

- `backend` → `ai-server`: `AI_SERVER_BASE_URL=http://ai-server:8090`
- `ai-server` → Ollama: `OLLAMA_BASE_URL=http://ollama:11434`
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
