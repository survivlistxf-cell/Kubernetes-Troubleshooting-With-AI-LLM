# 🐳 Running Kubexplain in Docker with Kubernetes Access

## Problem
When running the application in a Docker container, the container is isolated and cannot access:
- `kubectl` CLI tool
- Kubernetes cluster configuration
- Local Kubernetes resources (pods, nodes)

## Solution
We've configured Docker Compose to:
1. **Install `kubectl` inside the backend container**
2. **Mount your local `.kube/config` file** so the container can authenticate with your Kubernetes cluster

## 🚀 How to Use

### Prerequisites
1. ✅ Docker Desktop or Docker Engine installed
2. ✅ Kubernetes cluster running (Docker Desktop, Minikube, Kind, etc.)
3. ✅ `kubectl` configured locally (test with `kubectl cluster-info`)

### Step 1: Build and Start Containers
```bash
# Navigate to project directory
cd c:\Users\axine\Desktop\Licenta\Proiect

# Build Docker images and start containers
docker-compose up --build
```

### Step 2: Access the Application
- **Frontend**: http://localhost:3000
- **Backend API**: http://localhost:8080

### Step 3: Test Kubernetes Scanners
1. Go to **🖥️ Nodes Scanner** tab
2. Click **🔍 Scan for Nodes** → Should see your cluster nodes
3. Go to **📦 Pods Scanner** tab
4. Click **🔍 Scan for Pods** → Should see running pods

## 🔍 How It Works

### Backend Container (Docker)
```dockerfile
# kubectl is installed in the container
RUN apk add --no-cache curl && \
    curl -LO "https://dl.k8s.io/release/.../kubectl" && \
    chmod +x kubectl && mv kubectl /usr/local/bin/
```

### Volume Mount (docker-compose.yml)
```yaml
volumes:
  - ~/.kube/config:/root/.kube/config:ro
```

This mounts your local Kubernetes config file into the container as **read-only** (`ro`).

## 📋 File Structure
```
backend/
├── Dockerfile (UPDATED - now installs kubectl)
docker-compose.yml (UPDATED - added volume mount)
```

## ⚠️ Windows Path Considerations

On Windows, `~/.kube/config` is typically located at:
```
C:\Users\<USERNAME>\.kube\config
```

Docker Desktop on Windows handles the `~/` expansion automatically, so the volume mount should work seamlessly.

## 🐛 Troubleshooting

### Container can't find kubectl
```bash
# Check if kubectl is installed in container
docker exec proiect-backend which kubectl
```

### Can't authenticate with Kubernetes
```bash
# Verify config file exists on host
dir %USERPROFILE%\.kube\config

# Check config in container
docker exec proiect-backend cat /root/.kube/config
```

### Connection refused error
- Ensure Kubernetes cluster is running
- Test locally: `kubectl get nodes`
- Restart containers: `docker-compose restart backend`

## 🔐 Security Note
The `.kube/config` file contains sensitive credentials. In production:
- Use read-only mounts (`:ro` already applied)
- Consider using RBAC for container service accounts
- Use secret management tools (HashiCorp Vault, etc.)

## 📚 References
- [kubectl Installation](https://kubernetes.io/docs/tasks/tools/#kubectl)
- [kubeconfig Files](https://kubernetes.io/docs/concepts/configuration/organize-cluster-access-kubeconfig/)
- [Docker Compose Volumes](https://docs.docker.com/compose/compose-file/compose-file-v3/#volumes)
