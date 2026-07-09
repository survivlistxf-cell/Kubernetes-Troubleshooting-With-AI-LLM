# s08 | mode=dynamic | run=3 | 6.3s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it?

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

### What’s happening

* The container runs **nginx** which listens on **port 80** (the default HTTP port).  
* The pod’s **liveness probe** is defined as  

```
http-get http://:8081/ delay=5s timeout=1s period=5s
```

  – it tries to reach **port 8081** on the pod IP.  
* Because nothing is listening on 8081, the probe gets **“connection refused”**:

```
Liveness probe failed: Get "http://10.244.226.94:8081/": dial tcp 10.244.226.94:8081: connect: connection refused
```

* The kubelet marks the container **unhealthy** and kills it, then restarts it.  
* Since the probe keeps failing, the container is repeatedly restarted even though nginx itself is healthy.

**Root cause:** the liveness probe is pointing at the wrong port (and possibly the wrong path) for the nginx container.

---

### How to fix it

1. **Update the liveness probe** to use the correct port (80) and, if desired, a path that returns a 200‑OK response (e.g., `/` or `/healthz`).  
2. Apply the change to the workload (Deployment, Pod, etc.).

#### Example – edit the manifest

```bash
kubectl edit pod s08-bad-liveness -n kubexplain-eval
```

In the editor, locate the `livenessProbe` section and change it to:

```yaml
livenessProbe:
  httpGet:
    path: /
    port: 80          # <-- correct port for nginx
  initialDelaySeconds: 5
  periodSeconds: 5
  timeoutSeconds: 1
  successThreshold: 1
  failureThreshold: 2
```

Save and exit; the pod will be recreated with the corrected probe.

---

### Alternative fixes (choose one that fits your use‑case)

- **Expose a health‑check endpoint on 8081** inside the container (e.g., run a tiny HTTP server on that port) so the existing probe succeeds.  
- **Remove or disable the liveness probe** if you don’t need one (not recommended for production).  

```bash
# To remove the probe entirely (example for a Deployment)
kubectl patch deployment <name> -n kubexplain-eval \
  --type='json' -p='[{"op":"remove","path":"/spec/template/spec/containers/0/livenessProbe"}]'
```

---

### Quick checklist after the change

1. `kubectl get pod s08-bad-liveness -n kubexplain-eval` → status should become **Running** without recent restarts.  
2. `kubectl describe pod s08-bad-liveness -n kubexplain-eval` → no longer see `Liveness probe failed` events.  
3. Verify the application works as expected (e.g., `curl http://10.244.226.94:80/` from another pod).

Once the probe matches the actual service endpoint, the unnecessary restarts will stop.
