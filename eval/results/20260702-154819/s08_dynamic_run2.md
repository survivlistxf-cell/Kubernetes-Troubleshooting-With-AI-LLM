# s08 | mode=dynamic | run=2 | 9.5s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it?

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

### What’s happening?

* The pod **s08‑bad‑liveness** runs an **nginx** container that listens on **port 80** (the default for the nginx image).  
* Its **liveness probe** is defined as  

```
http-get http://:8081/ delay=5s timeout=1s period=5s
```

* When the kubelet runs the probe it tries to reach **http://10.244.226.94:8081/**.  
* Nothing is listening on **8081**, so the probe gets **“connection refused”** and the kubelet marks the container unhealthy:

```
Liveness probe failed: Get "http://10.244.226.94:8081/": dial tcp 10.244.226.94:8081: connect: connection refused
```

* Because the liveness probe fails, the kubelet kills the container and restarts it, even though nginx itself is perfectly healthy on port 80.

**Root cause:** The liveness probe is pointing at the wrong port (and/or path) for the container’s service.

---

### How to fix it

You have three typical options:

1. **Point the probe at the correct port** (the simplest fix).  
2. **Expose a health‑check endpoint on the port the probe expects** (e.g., configure nginx to listen on 8081).  
3. **Remove or disable the probe** if you don’t need one (not recommended for production).

Below are concrete commands for the most common fix – updating the probe to use port 80.

#### Option 1 – Patch the pod (or the owning Deployment/ReplicaSet)

```bash
kubectl patch pod s08-bad-liveness -n kubexplain-eval \
  --type='json' -p='[
    {"op":"replace","path":"/spec/containers/0/livenessProbe/httpGet/port","value":80},
    {"op":"replace","path":"/spec/containers/0/livenessProbe/httpGet/path","value":"/"}
  ]'
```

*If the pod is managed by a Deployment, patch the Deployment instead so the change survives a pod recreation:*

```bash
kubectl patch deployment <deployment-name> -n kubexplain-eval \
  --type='json' -p='[
    {"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe/httpGet/port","value":80},
    {"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe/httpGet/path","value":"/"}
  ]'
```

#### Option 2 – Edit the manifest manually

```bash
kubectl edit pod s08-bad-liveness -n kubexplain-eval
```

* In the editor locate the `livenessProbe` section and change:*

```yaml
livenessProbe:
  httpGet:
    path: /
    port: 80          # <-- change from 8081 to 80
  initialDelaySeconds: 5
  periodSeconds: 5
  timeoutSeconds: 1
  successThreshold: 1
  failureThreshold: 2
```

*Save and exit.* The kubelet will apply the new probe immediately.

#### Option 3 – Change nginx to listen on 8081 (if you prefer to keep the probe as‑is)

Edit the nginx configuration (e.g., via a ConfigMap) to add a `listen 8081;` directive, then redeploy the pod. This is more work and usually unnecessary unless you have a specific reason to expose the health endpoint on a non‑standard port.

---

### Verify the fix

1. **Check the pod’s events** – the “Unhealthy” messages should disappear.

   ```bash
   kubectl describe pod s08-bad-liveness -n kubexplain-eval
   ```

2. **Watch the container’s restart count** – it should stay at `0` (or stop increasing).

   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -o wide
   ```

3. **Optionally, test the endpoint directly** from inside the node or another pod:

   ```bash
   curl http://10.244.226.94:80/
   ```

   You should receive the default nginx page (HTTP 200).

---

### TL;DR

*The liveness probe is checking the wrong port (8081) while nginx serves on 80, causing the probe to fail and the container to be restarted.*  
Update the probe to use `port: 80` (or adjust nginx to listen on 8081) and the restarts will stop.
