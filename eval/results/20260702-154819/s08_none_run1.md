# s08 | mode=none | run=1 | 6.4s | needs_search=0

**Q:** My nginx pod 's08-bad-liveness' in namespace 'kubexplain-eval' keeps being restarted even though nginx itself works fine. What is the root cause and how do I fix it?

**Expected:** liveness probe checks the wrong port (8081 vs 80); fix the probe

---

## What is happening?

- The container **starts correctly** and serves Nginx on its default port **80** (you can see the container is in `Running` state and `Ready: True`).
- The pod definition includes a **liveness probe**:

```
http-get http://:8081/ delay=5s timeout=1s period=5s #success=1 #failure=2
```

- The probe tries to reach **port 8081** on the pod IP (`http://10.244.226.72:8081/`).  
- Nginx is **not listening on 8081**, so the TCP connection is refused:

```
Liveness probe failed: Get "http://10.244.226.72:8081/": dial tcp 10.244.226.72:8081: connect: connection refused
```

- Because the probe fails three times in a row (the default `failureThreshold` is 2, plus the initial failure), the kubelet kills the container and restarts it. That is why you see a `Restart Count: 1` and the `Killing` event.

## Root cause

The liveness probe is configured with the **wrong port** (and possibly the wrong path) for the Nginx container. The probe is checking a service that does not exist, causing the container to be marked unhealthy and restarted.

## How to fix it

1. **Update the liveness probe to match Nginx’s actual endpoint**  
   - Use port **80** (or the port you expose in the container).  
   - Use a path that Nginx serves, e.g. `/` or `/healthz` if you add a simple static file.

   Example probe definition (YAML snippet):

   ```yaml
   livenessProbe:
     httpGet:
       path: /
       port: 80
     initialDelaySeconds: 5
     periodSeconds: 5
     timeoutSeconds: 1
     failureThreshold: 2
   ```

2. **Apply the corrected manifest**  
   - If the pod is created directly from a manifest, edit that file and re‑apply it (`kubectl apply -f <file>`).  
   - If the pod is managed by a Deployment/ReplicaSet, edit the pod template in that controller and let it roll out a new replica.

3. **Verify**  
   - After the change, watch the pod events (`kubectl describe pod s08-bad-liveness -n kubexplain-eval`) and ensure no more “Killing … failed liveness probe” messages appear.  
   - The `Restart Count` should stay at 0 (or stop increasing).

### Optional: Remove the probe

If you do not need a liveness check for this simple Nginx pod, you can simply delete the `livenessProbe` section from the container spec. The pod will then be considered healthy as long as the container stays running.

---

**Bottom line:** The pod restarts because the liveness probe is probing the wrong port (8081). Align the probe with the actual Nginx port (80) or remove the probe, and the restarts will stop.
