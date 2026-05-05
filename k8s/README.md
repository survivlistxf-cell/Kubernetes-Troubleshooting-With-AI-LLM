# Kubexplain Kubernetes deploy

This folder contains a self-contained Kubernetes base for the application in a dedicated namespace.

## What it deploys

- `postgres` for the backend
- `backend` on port `8080`
- `frontend` exposed as `NodePort 30082`

## Apply

Replace the placeholder images in these files:

- `backend-deployment.yaml`
- `frontend-deployment.yaml`

Current DockerHub images:

- `axiiiiiiii/proiect_licenta-backend:latest`
- `axiiiiiiii/proiect_licenta-frontend:latest`

Then apply everything:

```bash
kubectl apply -k k8s
```

## OpenStack access

Expose the frontend `NodePort` through your Octavia load balancer:

- listener port: `80`
- member port: `30082`
- backend members: the internal IPs of the Kubernetes nodes

The floating IP you already attached to `lb-2` can stay as-is.
This layout does not require an Ingress controller; Octavia talks directly to the frontend NodePort.

## Build and push

Use these commands from the project root after logging in to DockerHub:

```bash
docker build -t axiiiiiiii/proiect_licenta-backend:latest ./backend
docker build -t axiiiiiiii/proiect_licenta-frontend:latest ./frontend

docker push axiiiiiiii/proiect_licenta-backend:latest
docker push axiiiiiiii/proiect_licenta-frontend:latest
```