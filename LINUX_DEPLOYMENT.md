# 🐧 Deploying Kubexplain on Linux with kubeadm

## Problem
When deploying the application on a Linux machine with kubeadm-configured Kubernetes cluster, the container cannot find `kubectl` even though it's installed on the host system.

## Root Cause
- Container has isolated filesystem
- `kubectl` is installed on host but not accessible inside container
- Container tries to find `kubectl` in standard paths that may not exist

## ✅ Solution

### Step 1: Update docker-compose.yml

Make sure your `docker-compose.yml` backend service includes volume mounts for `kubectl`:

```yaml
backend:
  build:
    context: ./backend
    dockerfile: Dockerfile
  container_name: proiect-backend
  ports:
    - "8080:8080"
  environment:
    - JAVA_OPTS=-Xmx512m -Xms256m
    - SPRING_PROFILES_ACTIVE=docker
    - KUBECONFIG=/root/.kube/config
  volumes:
    - ~/.kube/config:/root/.kube/config:ro           # Kubernetes config
    - ~/.kube:/root/.kube:ro                         # Kubernetes directory
    - /usr/local/bin/kubectl:/usr/local/bin/kubectl:ro  # kubectl binary (common location)
    - /usr/bin/kubectl:/usr/bin/kubectl:ro              # kubectl binary (alternative location)
  networks:
    - proiect-network
  restart: unless-stopped
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8080/api/hello"]
    interval: 30s
    timeout: 10s
    retries: 3
    start_period: 40s
```

### Step 2: Find kubectl Location on Your System

Before deploying, check where `kubectl` is installed:

```bash
# Check kubectl location
which kubectl
# OR
whereis kubectl
```

Example output:
```bash
ubuntu@master-1:~$ which kubectl
/usr/bin/kubectl
```

### Step 3: Mount the Correct kubectl Path

If `kubectl` is at a different location, update `docker-compose.yml`:

```yaml
volumes:
  - ~/.kube/config:/root/.kube/config:ro
  - ~/.kube:/root/.kube:ro
  - /usr/bin/kubectl:/usr/bin/kubectl:ro        # if kubectl is here
  - /opt/bin/kubectl:/opt/bin/kubectl:ro        # if kubectl is here
```

### Step 4: Deploy on Linux

```bash
# Navigate to project directory
cd /path/to/Proiect

# Stop old containers (if running)
docker-compose down

# Build and start containers
docker-compose up --build -d

# Wait for backend to be healthy
docker-compose ps
# Should show "proiect-backend ... (healthy)"
```

### Step 5: Test Kubernetes Access

```bash
# Test if container can access kubectl
docker exec proiect-backend kubectl get nodes

# Expected output:
# NAME       STATUS   ROLES           AGE     VERSION
# master-1   Ready    control-plane   2d21h   v1.27.0
# worker-1   Ready    <none>          2d21h   v1.27.0
# worker-2   Ready    <none>          2d21h   v1.27.0
```

### Step 6: Access Application

- **Frontend**: http://localhost:3000
- **Backend API**: http://localhost:8080

Test the scanners:
1. Go to **🖥️ Nodes Scanner** → Click "🔍 Scan for Nodes"
2. Go to **📦 Pods Scanner** → Click "🔍 Scan for Pods"

## 🔧 Advanced: Multi-Path kubectl Detection

The backend automatically tries multiple kubectl locations:
1. `/usr/local/bin/kubectl` (common on many Linux systems)
2. `/usr/bin/kubectl` (Debian/Ubuntu standard)
3. `/snap/bin/kubectl` (Snap installation)
4. Falls back to `which kubectl` via shell

This means even if kubectl is at an unusual location, it should be found.

## 🐛 Troubleshooting

### Container can't find kubectl

```bash
# Check if kubectl exists on host
which kubectl
ls -la /usr/bin/kubectl
ls -la /usr/local/bin/kubectl

# Check if mounted in container
docker exec proiect-backend which kubectl
docker exec proiect-backend ls -la /usr/bin/kubectl
```

### Still getting "kubectl not found" error

**Solution 1:** Verify volume mount in docker-compose.yml:
```yaml
volumes:
  - ~/.kube/config:/root/.kube/config:ro
  - ~/.kube:/root/.kube:ro
  - /usr/bin/kubectl:/usr/bin/kubectl:ro  # Add correct path
```

**Solution 2:** Check container logs:
```bash
docker-compose logs backend | grep kubectl
```

### Permission denied error

```bash
# Make kubectl executable on host
sudo chmod +x /usr/bin/kubectl
sudo chmod +x /usr/local/bin/kubectl

# Restart containers
docker-compose restart backend
```

### Connection refused when accessing Kubernetes

```bash
# Verify cluster is accessible from host
kubectl get nodes

# Check kubeconfig is correct
cat ~/.kube/config | head -20

# Restart containers
docker-compose down
docker-compose up -d
```

## 📝 File Changes Summary

### Modified Files:
1. **docker-compose.yml**
   - Added kubectl volume mounts
   - Ensures kubectl is accessible inside container

2. **backend/src/main/java/com/example/Application.java**
   - `isKubectlInstalled()` method now:
     - Tries multiple kubectl locations
     - Falls back to `which kubectl` command
     - Provides detailed logging for debugging

## 🔐 Security Notes

- All volume mounts use `:ro` (read-only) for security
- kubectl binary is mounted as read-only
- kubeconfig file is mounted as read-only
- Container cannot modify host kubectl or configuration

## 📚 References

- [kubeadm Installation](https://kubernetes.io/docs/setup/production-environment/tools/kubeadm/install-kubeadm/)
- [Docker Compose Volumes](https://docs.docker.com/compose/compose-file/compose-file-v3/#volumes)
- [kubectl Documentation](https://kubernetes.io/docs/reference/kubectl/)

## ✅ Success Indicators

After deployment, you should see:

```bash
# Nodes visible in UI
GET /api/scan-nodes → Returns JSON with nodes

# Pods visible in UI  
GET /api/scan-pods → Returns JSON with pods

# Both endpoints work without errors
# Container logs show "kubectl found at: /usr/bin/kubectl"
```
