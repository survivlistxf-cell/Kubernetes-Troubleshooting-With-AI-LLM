# s08 | mode=static | run=2 | 8.8s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it?

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

### What is happening?

| Observation | Meaning |
|-------------|---------|
| **Liveness probe**: `http-get http://:8081/ delay=5s timeout=1s period=5s` | The kubelet tries to reach **port 8081** on the pod’s IP every 5 seconds, starting 5 seconds after the container starts. |
| **Container image**: `nginx:1.25` (default Nginx) | Nginx listens on **port 80** (and only on that port unless you re‑configure it). |
| **Event**: `Liveness probe failed: Get "http://10.244.226.84:8081/": dial tcp 10.244.226.84:8081: connect: connection refused` | The kubelet cannot open a TCP connection to 8081 → the probe fails → the pod is marked **Unhealthy** and the container is killed and restarted. |
| **Restart Count**: still `0` in the current `describe` output because the failure just happened; after the restart you’ll see it increase. |

**Root cause** – The liveness probe is pointing at a port that the container does **not** expose. Nginx is running fine on port 80, but the probe asks the kubelet to contact port 8081, so the probe always fails and triggers a restart loop.

---

### How to fix it

You have three typical options:

1. **Make the probe match the real service**  
   Change the probe to hit the port Nginx actually serves (80) and, if you want a specific path, use `/` or any endpoint that returns `200`. Example:

   ```yaml
   livenessProbe:
     httpGet:
       path: /
       port: 80          # <-- match Nginx port
     initialDelaySeconds: 5
     periodSeconds: 5
     timeoutSeconds: 1
   ```

2. **Expose the expected port inside the container**  
   If you deliberately want the probe on 8081, configure Nginx (or a side‑car) to listen on that port, e.g. add a `listen 8081;` block in the Nginx config and expose it with `containerPort: 8081`. This is more work and usually unnecessary for a simple “nginx‑only” pod.

3. **Remove or disable the liveness probe** (not recommended for production)  
   If you don’t need a liveness check, simply delete the `livenessProbe` section. The pod will then stay up as long as the container stays running.

---

### Applying the fix

Assuming the pod is created by a **Deployment** (or other controller), edit the manifest:

```bash
kubectl edit deployment s08-bad-liveness -n kubexplain-eval
```

Locate the `livenessProbe` under `spec.template.spec.containers[0]` and change the `port` to `80` (or remove the probe). Save and exit; the controller will roll out a new replica with the corrected spec.

If the pod is a **stand‑alone Pod** (no controller), you can delete and recreate it with the corrected spec:

```bash
kubectl delete pod s08-bad-liveness -n kubexplain-eval
kubectl apply -f corrected-pod.yaml
```

*(Replace `corrected-pod.yaml` with the pod definition that contains the updated probe.)*

---

### Verify the fix

1. **Watch the pod**:

   ```bash
   kubectl get pod s08-bad-liveness -n kubexplain-eval -w
   ```

2. **Check events after a minute**:

   ```bash
   kubectl describe pod s08-bad-liveness -n kubexplain-eval | grep -i event -A5
   ```

You should no longer see `Liveness probe failed` messages, and the `Restart Count` should stay at `0` (or only increase if you manually restart the pod).

---

### TL;DR

*Root cause*: Liveness probe is checking port 8081, but the Nginx container only serves on port 80 → probe always fails → kubelet restarts the container.  
*Fix*: Align the probe with the actual port (change `port: 80`), or expose port 8081 inside the container, or remove the probe. After updating the pod/deployment, the restarts stop.
